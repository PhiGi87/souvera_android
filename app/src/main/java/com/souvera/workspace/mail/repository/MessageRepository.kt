/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.repository

import android.content.Context
import com.souvera.workspace.dav.DavAccount
import com.souvera.workspace.mail.db.SouveraMailDatabase
import com.souvera.workspace.mail.db.entity.MailboxKind
import com.souvera.workspace.mail.db.entity.MessageEntity
import com.souvera.workspace.mail.model.AttachmentDownload
import com.souvera.workspace.mail.model.MessageBody
import com.souvera.workspace.mail.model.OutgoingMessage
import com.souvera.workspace.mail.net.jmap.JmapApi
import com.souvera.workspace.mail.net.jmap.JmapClient
import com.souvera.workspace.mail.net.jmap.JmapMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MessageRepository(context: Context) {

    private val appContext = context.applicationContext
    private val db = SouveraMailDatabase.getInstance(context)

    fun observeMessages(accountName: String, mailboxPath: String): Flow<List<MessageEntity>> =
        db.messageDao().observeMessages(mailboxId(accountName, mailboxPath), 50)

    suspend fun messageById(accountName: String, mailboxPath: String, emailId: String): MessageEntity? =
        db.messageDao().getByMailboxAndId(mailboxId(accountName, mailboxPath), emailId)

    /** Accountweiter Lookup — deckt Sieve-sortierte Mails ab, deren Ordner wir nicht kennen. */
    suspend fun messageByEmailIdAnywhere(accountName: String, emailId: String): MessageEntity? =
        db.messageDao().getByAccountAndEmailId(accountName, emailId)

    suspend fun searchMessages(accountName: String, query: String, dav: DavAccount): List<MessageEntity> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        val client = jmapClient(dav)
        val api = JmapApi(client)
        // Session-ID verwenden (z. B. base32 "f") — Email/query verlangt accountId.
        val accountId = client.refreshSession().primaryAccountId
        return try {
            val resp = api.queryEmails(accountId, "", filterText = trimmed, limit = 50)
            val ids = resp.optJSONArray("ids") ?: JSONArray()
            if (ids.length() == 0) return emptyList()
            val idList = (0 until ids.length()).map { ids.getString(it) }
            val list = api.getEmails(accountId, idList)
            (0 until list.length()).mapNotNull { i ->
                val json = list.getJSONObject(i)
                // Use empty mailboxId — these are search results, not pinned to a mailbox.
                JmapMapper.mapEmail(accountName, "${accountName}:search", json)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun syncMessages(
        accountName: String,
        mailboxPath: String,
        dav: DavAccount
    ): MailResult<List<MessageEntity>> = mailCall("Message sync failed") {
        val client = jmapClient(dav)
        val api = JmapApi(client)
        val session = client.refreshSession()
        val mid = mailboxId(accountName, mailboxPath)

        // Shared mailbox: resolve the JMAP account ID. Persisted value from
        // mailbox sync wins; session name-matching is only a fallback.
        val (jmapAccountId, lookupPath) = if (mailboxPath.contains('/')) {
            val owner = mailboxPath.substringBefore('/')
            val subPath = mailboxPath.substringAfter('/')
            val persisted = db.mailboxDao().findById(mid)?.jmapAccountId
            val sharedAccId = persisted?.takeIf { it.isNotBlank() } ?: run {
                val sessionJson = client.getSessionJson()
                val accounts = sessionJson?.optJSONObject("accounts")
                var found: String? = null
                accounts?.keys()?.forEach { key ->
                    val acc = accounts.optJSONObject(key) ?: return@forEach
                    if (acc.optString("name") == owner && !acc.optBoolean("isPersonal", true)) {
                        found = key
                    }
                }
                found
            }
            if (sharedAccId.isNullOrBlank()) {
                throw IllegalStateException("No JMAP account resolved for shared mailbox \"$mailboxPath\"")
            }
            Pair(sharedAccId, subPath)
        } else {
            // Persoenliches Postfach: Session-ID verwenden.
            Pair(session.primaryAccountId, mailboxPath)
        }

        // Find the JMAP-id of this mailbox.
        val jmapMailboxId = db.mailboxDao().findById(mid)?.jmapId
            ?: run {
                val mboxes = api.getMailboxes(jmapAccountId)
                var found: String? = null
                for (i in 0 until mboxes.length()) {
                    val mb = mboxes.getJSONObject(i)
                    if (mb.optString("name").equals(lookupPath, ignoreCase = true)) {
                        found = mb.optString("id")
                        val entity = JmapMapper.mapMailbox(accountName, mb, lookupPath)
                        db.mailboxDao().upsertAll(listOf(entity.copy(jmapId = entity.jmapId ?: found)))
                        break
                    }
                }
                found ?: lookupPath
            }

        val sort = JSONArray().apply {
            put(JSONObject().apply {
                put("property", "receivedAt")
                put("isAscending", false)
            })
        }
        val queryResp = api.queryEmails(jmapAccountId, jmapMailboxId, sort, limit = 100)
        val emailIds = queryResp.optJSONArray("ids") ?: JSONArray()
        if (emailIds.length() == 0) {
            // Still upsert empty to prune removed messages from the DB.
            db.messageDao().deleteMissing(mid, emptyList())
            db.messageDao().upsertAll(emptyList())
            return@mailCall emptyList<MessageEntity>()
        }
        val idList = (0 until emailIds.length()).map { emailIds.getString(it) }
        val list = api.getEmails(jmapAccountId, idList)
        val entities = (0 until list.length()).mapNotNull { i ->
            val json = list.getJSONObject(i)
            JmapMapper.mapEmail(accountName, mid, json)
        }
        db.messageDao().deleteMissing(mid, idList)
        db.messageDao().upsertAll(entities)
        entities
    }

    /**
     * Resolves the JMAP accountId for a mailbox path (personal or shared). Shared paths resolve
     * STRICTLY: if no session account matches, an error is thrown instead of silently falling back
     * to the personal account — that silent fallback was what fetched the wrong mail's body.
     */
    private suspend fun resolveAccountId(mailboxPath: String, dav: DavAccount): String {
        if (!mailboxPath.contains('/')) {
            return jmapClient(dav).refreshSession().primaryAccountId
        }
        val owner = mailboxPath.substringBefore('/')
        val client = jmapClient(dav)
        if (client.getSessionJson() == null) client.refreshSession()
        val sessionJson = client.getSessionJson()
        val accounts = sessionJson?.optJSONObject("accounts")
        accounts?.keys()?.forEach { key ->
            val acc = accounts.optJSONObject(key) ?: return@forEach
            if (acc.optString("name") == owner && !acc.optBoolean("isPersonal", true)) {
                return key
            }
        }
        throw IllegalStateException("No JMAP account resolved for shared mailbox \"$mailboxPath\"")
    }

    /** Resolves the JMAP accountId for a mailbox, preferring the value persisted during mailbox sync. */
    private suspend fun resolveAccountIdFor(accountName: String, mailboxPath: String, dav: DavAccount): String {
        val persisted = db.mailboxDao().findById(mailboxId(accountName, mailboxPath))?.jmapAccountId
        return persisted?.takeIf { it.isNotBlank() } ?: resolveAccountId(mailboxPath, dav)
    }

    suspend fun fetchMessageBody(
        accountName: String,
        mailboxPath: String,
        emailId: String,
        dav: DavAccount
    ): MailResult<MessageBody> = mailCall("Body fetch failed") {
        val client = jmapClient(dav)
        val api = JmapApi(client)
        val accountId = resolveAccountIdFor(accountName, mailboxPath, dav)

        val bodyProps = JSONArray(listOf(
            "partId", "blobId", "size", "type", "name"
        ))
        val list = api.getEmails(accountId, listOf(emailId), bodyProps)
        val json = list.getJSONObject(0)
        val mapped = JmapMapper.mapBody(json)

        val textBlobId = json.optJSONArray("textBody")
            ?.optJSONObject(0)?.optString("blobId", null)
            ?.takeIf { it.isNotBlank() }
        val htmlBlobId = json.optJSONArray("htmlBody")
            ?.optJSONObject(0)?.optString("blobId", null)
            ?.takeIf { it.isNotBlank() }

        val plainText = textBlobId?.let {
            String(client.downloadBlob(accountId, it, "text/plain"), Charsets.UTF_8)
        }
        var html = htmlBlobId?.let {
            String(client.downloadBlob(accountId, it, "text/html"), Charsets.UTF_8)
        }
        // Plaintext-Mails mit auto-generiertem HTML (kein <br>/Block-Tag)
        // würden als HTML die Zeilenumbrüche verlieren — dann Plaintext
        // bevorzugen, damit die Absätze erhalten bleiben.
        val htmlHasBreaks = html != null && Regex(
            "(?i)<(br|p|div|li|h[1-6]|pre|blockquote|table|ul|ol|tr)[\\s>/]"
        ).containsMatchIn(html)
        if (html != null && !htmlHasBreaks && !plainText.isNullOrBlank()) {
            html = null
        }
        MessageBody(plainText, html, mapped.attachments)
    }

    suspend fun sendMessage(
        accountName: String,
        dav: DavAccount,
        fromAddress: String,
        outgoing: OutgoingMessage
    ): MailResult<Unit> = mailCall("Send failed") {
        val client = jmapClient(dav)
        val api = JmapApi(client)
        val accountId = client.refreshSession().primaryAccountId

        // Upload attachment blobs.
        val blobIds = outgoing.attachments.map { att ->
            val bytes = withContext(Dispatchers.IO) {
                appContext.contentResolver.openInputStream(android.net.Uri.parse(att.uri))
                    ?.use { it.readBytes() }
            } ?: throw RuntimeException("Cannot read attachment ${att.name}")
            client.uploadBlob(accountId, bytes, att.mimeType).blobId
        }

        // Find drafts mailbox.
        val draftsPath = db.mailboxDao().findByKind(accountName, MailboxKind.DRAFTS)?.path ?: "Drafts"
        val draftsJmapId = db.mailboxDao().findById(mailboxId(accountName, draftsPath))?.jmapId
            ?: draftsPath

        val draftResp = api.createDraft(
            accountId = accountId,
            mailboxId = draftsJmapId,
            fromAddress = fromAddress,
            toAddresses = outgoing.to,
            ccAddresses = outgoing.cc,
            bccAddresses = outgoing.bcc,
            subject = outgoing.subject,
            htmlBody = outgoing.bodyHtml,
            plainText = if (outgoing.bodyHtml.isNullOrBlank()) outgoing.body else null,
            inReplyTo = outgoing.inReplyTo,
            blobIds = blobIds
        )
        val draftId = draftResp.optJSONObject("created")?.keys()?.next()
            ?: throw RuntimeException("Draft not created")

        // Resolve the JMAP identity ID matching fromAddress.
        val identityId = run {
            val identities = api.getIdentities(accountId)
            var foundId: String? = null
            for (i in 0 until identities.length()) {
                val idt = identities.getJSONObject(i)
                if (idt.optString("email") == fromAddress) {
                    foundId = idt.optString("id")
                    break
                }
            }
            foundId ?: fromAddress
        }

        // Submit.
        api.submitEmail(accountId, draftId, identityId)
    }

    suspend fun fetchAttachment(
        accountName: String,
        mailboxPath: String,
        emailId: String,
        index: Int,
        dav: DavAccount
    ): MailResult<AttachmentDownload> = mailCall("Attachment fetch failed") {
        val client = jmapClient(dav)
        val api = JmapApi(client)
        val accountId = resolveAccountIdFor(accountName, mailboxPath, dav)

        val bodyProps = JSONArray(listOf("partId", "blobId", "size", "type", "name"))
        val list = api.getEmails(accountId, listOf(emailId), bodyProps)
        val json = list.getJSONObject(0)
        val attArr = json.optJSONArray("attachments")
            ?: throw RuntimeException("No attachments")
        val att = attArr.getJSONObject(index)
            ?: throw RuntimeException("Attachment index out of bounds")
        val blobId = att.optString("blobId")
            ?: throw RuntimeException("Attachment has no blobId")
        val mimeType = att.optString("type", "application/octet-stream")
        val name = att.optString("name", "attachment")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")

        val bytes = client.downloadBlob(accountId, blobId, mimeType)
        val directory = File(appContext.cacheDir, ATTACHMENT_CACHE_DIR).apply { mkdirs() }
        val file = File(directory, "${emailId}_${index}_$name")
        file.writeBytes(bytes)
        AttachmentDownload(file, mimeType)
    }

    suspend fun setRead(
        accountName: String,
        mailboxPath: String,
        emailId: String,
        isRead: Boolean,
        dav: DavAccount
    ): MailResult<Unit> = setKeyword(accountName, mailboxPath, emailId, mailboxId(accountName, mailboxPath), dav,
        if (isRead) mapOf("\$seen" to true) else mapOf(),
        if (isRead) emptyList() else listOf("\$seen")
    )

    suspend fun setFlagged(
        accountName: String,
        mailboxPath: String,
        emailId: String,
        isFlagged: Boolean,
        dav: DavAccount
    ): MailResult<Unit> = setKeyword(accountName, mailboxPath, emailId, mailboxId(accountName, mailboxPath), dav,
        if (isFlagged) mapOf("\$flagged" to true) else mapOf(),
        if (isFlagged) emptyList() else listOf("\$flagged")
    )

    suspend fun delete(
        accountName: String,
        mailboxPath: String,
        emailId: String,
        dav: DavAccount
    ): MailResult<Unit> = mailCall("Delete failed") {
        val client = jmapClient(dav)
        val api = JmapApi(client)
        val accountId = resolveAccountIdFor(accountName, mailboxPath, dav)
        val trash = db.mailboxDao().findByKind(accountName, MailboxKind.TRASH)
        val mid = mailboxId(accountName, mailboxPath)
        if (trash != null && trash.id != mid) {
            val trashJmap = trash.jmapId ?: trash.path
            api.moveEmails(accountId, listOf(emailId), trashJmap)
        } else {
            api.deleteEmails(accountId, listOf(emailId))
        }
        db.messageDao().delete(mid, emailId)
    }

    /** Verschiebt eine Mail in den Junk-Ordner (Spam). */
    suspend fun spam(
        accountName: String,
        sourcePath: String,
        emailId: String,
        dav: DavAccount
    ): MailResult<Unit> = mailCall("Move to spam failed") {
        val client = jmapClient(dav)
        val api = JmapApi(client)
        val accountId = resolveAccountIdFor(accountName, sourcePath, dav)
        val junk = db.mailboxDao().findByKind(accountName, MailboxKind.JUNK)
        val mid = mailboxId(accountName, sourcePath)
        if (junk != null && junk.id != mid) {
            val junkJmap = junk.jmapId ?: junk.path
            api.moveEmails(accountId, listOf(emailId), junkJmap)
            db.messageDao().delete(mid, emailId)
        } else {
            throw IllegalStateException("No junk mailbox available")
        }
    }

    suspend fun move(
        accountName: String,
        sourcePath: String,
        emailId: String,
        targetPath: String,
        dav: DavAccount
    ): MailResult<Unit> = mailCall("Move failed") {
        val client = jmapClient(dav)
        val api = JmapApi(client)
        val accountId = resolveAccountIdFor(accountName, sourcePath, dav)
        val targetMailbox = db.mailboxDao().findById(mailboxId(accountName, targetPath))
        val targetJmap = targetMailbox?.jmapId ?: targetPath
        api.moveEmails(accountId, listOf(emailId), targetJmap)
        db.messageDao().delete(mailboxId(accountName, sourcePath), emailId)
    }

    /* ---------- helpers ----------------------------------------------- */

    private suspend fun setKeyword(
        accountName: String,
        mailboxPath: String,
        emailId: String,
        mailboxId: String,
        dav: DavAccount,
        add: Map<String, Boolean>,
        remove: List<String>
    ): MailResult<Unit> = mailCall("Flag change failed") {
        val client = jmapClient(dav)
        val api = JmapApi(client)
        val accountId = resolveAccountIdFor(accountName, mailboxPath, dav)
        api.setEmailFlags(accountId, listOf(emailId), add, remove)
        // Local update AFTER server confirmation — no window for parallel sync to revert.
        if (add.containsKey("\$seen") || remove.contains("\$seen")) {
            db.messageDao().markRead(mailboxId, emailId, add.containsKey("\$seen"))
        }
        if (add.containsKey("\$flagged") || remove.contains("\$flagged")) {
            db.messageDao().markFlagged(mailboxId, emailId, add.containsKey("\$flagged"))
        }
    }

    private fun jmapClient(dav: DavAccount): JmapClient = JmapClient(dav)

    companion object {
        private const val ATTACHMENT_CACHE_DIR = "attachments"

        fun mailboxId(accountName: String, path: String): String = "$accountName:$path"
    }
}
