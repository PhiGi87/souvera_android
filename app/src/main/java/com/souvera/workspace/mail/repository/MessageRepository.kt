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

    fun searchMessages(accountName: String, query: String): Flow<List<MessageEntity>> =
        db.messageDao().searchMessages(accountName, query, 50)

    suspend fun syncMessages(
        accountName: String,
        mailboxPath: String,
        dav: DavAccount
    ): MailResult<List<MessageEntity>> = mailCall("Message sync failed") {
        val client = jmapClient(dav)
        val api = JmapApi(client)
        val session = client.refreshSession()

        // Shared mailbox: resolve the JMAP account ID from the session.
        val (jmapAccountId, lookupPath) = if (mailboxPath.contains('/')) {
            val owner = mailboxPath.substringBefore('/')
            val subPath = mailboxPath.substringAfter('/')
            val sessionJson = client.getSessionJson()
            val accounts = sessionJson?.optJSONObject("accounts")
            var sharedAccId: String? = null
            accounts?.keys()?.forEach { key ->
                val acc = accounts.optJSONObject(key) ?: return@forEach
                if (acc.optString("name") == owner && !acc.optBoolean("isPersonal", true)) {
                    sharedAccId = key
                }
            }
            Pair(sharedAccId ?: session.primaryAccountId, subPath)
        } else {
            Pair(session.primaryAccountId, mailboxPath)
        }

        val mid = mailboxId(accountName, mailboxPath)

        // Find the JMAP-id of this mailbox.
        val jmapMailboxId = db.mailboxDao().findById(mid)?.jmapId
            ?: run {
                val mboxes = api.getMailboxes(jmapAccountId)
                var found: String? = null
                for (i in 0 until mboxes.length()) {
                    val mb = mboxes.getJSONObject(i)
                    if (mb.optString("name") == lookupPath) {
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
        // Preserve locally-set read/flagged state — upsertAll would overwrite
        // them with the server state when a parallel JMAP flag update hasn't
        // been committed yet.
        val oldState = mutableMapOf<String, Pair<Boolean, Boolean>>()
        for (id in idList) {
            val mbId = mid
            db.messageDao().getByMailboxAndId(mbId, id)?.let {
                oldState[id] = Pair(it.isRead, it.isFlagged)
            }
        }
        val entities = (0 until list.length()).mapNotNull { i ->
            val json = list.getJSONObject(i)
            val entity = JmapMapper.mapEmail(accountName, mid, json)
            val saved = oldState[entity.emailId]
            if (saved != null) entity.copy(isRead = saved.first, isFlagged = saved.second) else entity
        }
        db.messageDao().deleteMissing(mid, idList)
        db.messageDao().upsertAll(entities)
        entities
    }

    suspend fun fetchMessageBody(
        mailboxPath: String,
        emailId: String,
        dav: DavAccount
    ): MailResult<MessageBody> = mailCall("Body fetch failed") {
        val client = jmapClient(dav)
        val api = JmapApi(client)
        val accountId = client.refreshSession().primaryAccountId

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
        val html = htmlBlobId?.let {
            String(client.downloadBlob(accountId, it, "text/html"), Charsets.UTF_8)
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

        // Submit.
        api.submitEmail(accountId, draftId, fromAddress)
    }

    suspend fun fetchAttachment(
        mailboxPath: String,
        emailId: String,
        index: Int,
        dav: DavAccount
    ): MailResult<AttachmentDownload> = mailCall("Attachment fetch failed") {
        val client = jmapClient(dav)
        val api = JmapApi(client)
        val accountId = client.refreshSession().primaryAccountId

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
    ): MailResult<Unit> = setKeyword(emailId, mailboxId(accountName, mailboxPath), dav,
        if (isRead) mapOf("\$seen" to true) else mapOf(),
        if (isRead) emptyList() else listOf("\$seen")
    )

    suspend fun setFlagged(
        accountName: String,
        mailboxPath: String,
        emailId: String,
        isFlagged: Boolean,
        dav: DavAccount
    ): MailResult<Unit> = setKeyword(emailId, mailboxId(accountName, mailboxPath), dav,
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
        val accountId = client.refreshSession().primaryAccountId
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

    suspend fun move(
        accountName: String,
        sourcePath: String,
        emailId: String,
        targetPath: String,
        dav: DavAccount
    ): MailResult<Unit> = mailCall("Move failed") {
        val client = jmapClient(dav)
        val api = JmapApi(client)
        val accountId = client.refreshSession().primaryAccountId
        val targetMailbox = db.mailboxDao().findById(mailboxId(accountName, targetPath))
        val targetJmap = targetMailbox?.jmapId ?: targetPath
        api.moveEmails(accountId, listOf(emailId), targetJmap)
        db.messageDao().delete(mailboxId(accountName, sourcePath), emailId)
    }

    /* ---------- helpers ----------------------------------------------- */

    private suspend fun setKeyword(
        emailId: String,
        mailboxId: String,
        dav: DavAccount,
        add: Map<String, Boolean>,
        remove: List<String>
    ): MailResult<Unit> = mailCall("Flag change failed") {
        // Optimistic local update — mark first, then sync to server.
        // This prevents the parallel sync from reverting the flag.
        if (add.containsKey("\$seen") || remove.contains("\$seen")) {
            db.messageDao().markRead(mailboxId, emailId, add.containsKey("\$seen"))
        }
        if (add.containsKey("\$flagged") || remove.contains("\$flagged")) {
            db.messageDao().markFlagged(mailboxId, emailId, add.containsKey("\$flagged"))
        }
        val client = jmapClient(dav)
        val api = JmapApi(client)
        val accountId = client.refreshSession().primaryAccountId
        api.setEmailFlags(accountId, listOf(emailId), add, remove)
    }

    private fun jmapClient(dav: DavAccount): JmapClient = JmapClient(dav)

    companion object {
        private const val ATTACHMENT_CACHE_DIR = "attachments"

        fun mailboxId(accountName: String, path: String): String = "$accountName:$path"
    }
}
