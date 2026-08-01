/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.souvera.workspace.dav.DavAccount
import com.souvera.workspace.mail.MailSettings
import com.souvera.workspace.mail.db.SouveraMailDatabase
import com.souvera.workspace.mail.db.entity.MailboxKind
import com.souvera.workspace.mail.db.entity.MessageEntity
import com.souvera.workspace.mail.model.AttachmentDownload
import com.souvera.workspace.mail.model.LoadedAttachment
import com.souvera.workspace.mail.model.MessageBody
import com.souvera.workspace.mail.model.OutgoingMessage
import com.souvera.workspace.mail.net.ImapMessageMapper
import com.souvera.workspace.mail.net.MailSession
import com.souvera.workspace.mail.net.MimeBodyExtractor
import com.souvera.workspace.mail.net.mailboxId
import jakarta.mail.FetchProfile
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.UIDFolder
import jakarta.mail.internet.MimeMessage
import java.io.File
import kotlinx.coroutines.flow.Flow
import org.eclipse.angus.mail.imap.IMAPFolder

class MessageRepository(context: Context) {

    private val appContext = context.applicationContext
    private val db = SouveraMailDatabase.getInstance(context)
    private val settings = MailSettings(context)

    fun observeMessages(accountName: String, mailboxPath: String): Flow<List<MessageEntity>> =
        db.messageDao().observeMessages(mailboxId(accountName, mailboxPath), settings.messageLimit)

    suspend fun messageByUid(accountName: String, mailboxPath: String, uid: Long): MessageEntity? =
        db.messageDao().getByMailboxAndUid(mailboxId(accountName, mailboxPath), uid)

    fun searchMessages(accountName: String, query: String): Flow<List<MessageEntity>> =
        db.messageDao().searchMessages(accountName, query, settings.messageLimit)

    suspend fun syncMessages(
        accountName: String,
        mailboxPath: String,
        dav: DavAccount
    ): MailResult<List<MessageEntity>> = mailCall("Message sync failed") {
        val id = mailboxId(accountName, mailboxPath)
        val cached = db.mailboxDao().findById(id)
        val store = MailSession(dav).openImapStore()
        val folder = store.getFolder(mailboxPath) as IMAPFolder
        folder.open(Folder.READ_ONLY)
        if (cached != null && cached.uidValidity != folder.uidValidity) {
            db.messageDao().deleteAllInMailbox(id)
        }
        val total = folder.messageCount
        val first = maxOf(1, total - settings.messageLimit + 1)
        val messages = if (total > 0) folder.getMessages(first, total) else emptyArray()

        var envelopeFailed = false
        val entities = if (messages.isNotEmpty()) {
            try {
                folder.fetch(
                    messages,
                    FetchProfile().apply {
                        add(FetchProfile.Item.ENVELOPE)
                        add(FetchProfile.Item.FLAGS)
                        add(FetchProfile.Item.SIZE)
                        add(UIDFolder.FetchProfileItem.UID)
                    }
                )
                messages.mapNotNull { msg ->
                    mapEntityOrNull(accountName, id, folder, msg)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Batch envelope fetch failed for $mailboxPath, fallback per-message: ${e.message}")
                envelopeFailed = true
                messages.mapNotNull { msg ->
                    try {
                        folder.fetch(
                            arrayOf(msg),
                            FetchProfile().apply {
                                add(FetchProfile.Item.ENVELOPE)
                                add(FetchProfile.Item.FLAGS)
                                add(FetchProfile.Item.SIZE)
                                add(UIDFolder.FetchProfileItem.UID)
                            }
                        )
                        mapEntityOrNull(accountName, id, folder, msg)
                    } catch (ignored: Exception) {
                        Log.w(TAG, "Skipping message in $mailboxPath: ${ignored.message}")
                        null
                    }
                }
            }
        } else {
            emptyList()
        }

        if (entities.isNotEmpty()) {
            db.messageDao().upsertAll(entities)
            val fetchedUids = entities.map { it.uid }
            db.messageDao().deleteMissingInRange(id, fetchedUids.min(), fetchedUids.max(), fetchedUids)
            db.mailboxDao().updateSyncState(id, folder.uidValidity, entities.maxOfOrNull { it.uid } ?: 0L)
        } else if (total > 0 && envelopeFailed) {
            Log.w(TAG, "All $total messages in $mailboxPath could not be parsed — keeping cached data")
        }

        folder.close(false)
        entities
    }

    private fun mapEntityOrNull(
        accountName: String,
        mailboxId: String,
        folder: IMAPFolder,
        message: Message
    ): MessageEntity? = try {
        ImapMessageMapper.toEntity(accountName, mailboxId, folder, message)
    } catch (e: Exception) {
        Log.w(TAG, "Skipping message (envelope parse failed): ${e.message}")
        null
    }

    suspend fun fetchMessageBody(mailboxPath: String, uid: Long, dav: DavAccount): MailResult<MessageBody> =
        mailCall("Loading message failed") {
            val store = MailSession(dav).openImapStore()
            val folder = store.getFolder(mailboxPath) as IMAPFolder
            folder.open(Folder.READ_ONLY)
            val message = folder.getMessageByUID(uid)
            val body = if (message != null) MimeBodyExtractor.extract(message) else MessageBody(null, null)
            folder.close(false)
            body
        }

    suspend fun sendMessage(
        accountName: String,
        fromAddress: String,
        outgoing: OutgoingMessage,
        dav: DavAccount
    ): MailResult<Unit> = mailCall("Sending message failed") {
        val session = MailSession(dav)
        val message = session.buildMessage(fromAddress, outgoing, loadAttachments(outgoing))
        val transport = session.openSmtpTransport()
        try {
            transport.sendMessage(message, message.allRecipients)
        } finally {
            transport.close()
        }
        appendToSent(accountName, dav, message)
    }

    suspend fun fetchAttachment(
        mailboxPath: String, uid: Long, index: Int, dav: DavAccount
    ): MailResult<AttachmentDownload> =
        mailCall("Loading attachment failed") {
            val store = MailSession(dav).openImapStore()
            val folder = store.getFolder(mailboxPath) as IMAPFolder
            folder.open(Folder.READ_ONLY)
            val message = folder.getMessageByUID(uid) ?: error("Message no longer exists")
            val part = MimeBodyExtractor.attachmentParts(message).getOrNull(index)
                ?: error("Attachment no longer exists")
            val safeName = MimeBodyExtractor.decodedName(part).replace(Regex("[^A-Za-z0-9._-]"), "_")
            val directory = File(appContext.cacheDir, ATTACHMENT_CACHE_DIR).apply { mkdirs() }
            val file = File(directory, "${uid}_${index}_$safeName")
            part.inputStream.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            folder.close(false)
            val rawType = runCatching { part.contentType }.getOrNull() ?: "application/octet-stream"
            AttachmentDownload(file, rawType.substringBefore(';').trim())
        }

    suspend fun setRead(
        accountName: String,
        mailboxPath: String,
        uid: Long,
        isRead: Boolean,
        dav: DavAccount
    ): MailResult<Unit> = setFlag(mailboxPath, uid, Flags.Flag.SEEN, isRead, dav) {
        db.messageDao().markRead(mailboxId(accountName, mailboxPath), uid, isRead)
    }

    suspend fun setFlagged(
        accountName: String,
        mailboxPath: String,
        uid: Long,
        isFlagged: Boolean,
        dav: DavAccount
    ): MailResult<Unit> = setFlag(mailboxPath, uid, Flags.Flag.FLAGGED, isFlagged, dav) {
        db.messageDao().markFlagged(mailboxId(accountName, mailboxPath), uid, isFlagged)
    }

    suspend fun delete(accountName: String, mailboxPath: String, uid: Long, dav: DavAccount): MailResult<Unit> =
        mailCall("Moving message failed") {
            val trash = db.mailboxDao().findByKind(accountName, MailboxKind.TRASH)
            relocate(accountName, mailboxPath, trash?.path?.takeIf { it != mailboxPath }, uid, dav)
        }

    suspend fun move(
        accountName: String,
        sourcePath: String,
        targetPath: String,
        uid: Long,
        dav: DavAccount
    ): MailResult<Unit> = mailCall("Moving message failed") {
        relocate(accountName, sourcePath, targetPath, uid, dav)
    }

    private suspend fun setFlag(
        mailboxPath: String,
        uid: Long,
        flag: Flags.Flag,
        value: Boolean,
        dav: DavAccount,
        onLocalUpdate: suspend () -> Unit
    ): MailResult<Unit> = mailCall("Updating message failed") {
        val store = MailSession(dav).openImapStore()
        val folder = store.getFolder(mailboxPath) as IMAPFolder
        folder.open(Folder.READ_WRITE)
        folder.getMessageByUID(uid)?.setFlag(flag, value)
        folder.close(false)
        onLocalUpdate()
    }

    private fun loadAttachments(outgoing: OutgoingMessage): List<LoadedAttachment> {
        val loaded = outgoing.attachments.map { attachment ->
            val bytes = appContext.contentResolver.openInputStream(Uri.parse(attachment.uri))
                ?.use { it.readBytes() }
                ?: error("Cannot read attachment ${attachment.name}")
            LoadedAttachment(attachment.name, attachment.mimeType, bytes)
        }
        val totalBytes = loaded.sumOf { it.bytes.size.toLong() }
        require(totalBytes <= MAX_ATTACHMENT_BYTES) { "Attachments exceed 25 MB in total" }
        return loaded
    }

    private suspend fun appendToSent(accountName: String, dav: DavAccount, message: MimeMessage) {
        val sent = db.mailboxDao().findByKind(accountName, MailboxKind.SENT) ?: return
        try {
            val store = MailSession(dav).openImapStore()
            val folder = store.getFolder(sent.path)
            folder.open(Folder.READ_WRITE)
            message.setFlag(Flags.Flag.SEEN, true)
            folder.appendMessages(arrayOf(message))
            folder.close(false)
        } catch (ignored: Exception) {
            Log.w(TAG, "Sent-folder append failed (message was still sent): ${ignored.message}")
        }
    }

    private suspend fun relocate(
        accountName: String,
        sourcePath: String,
        targetPath: String?,
        uid: Long,
        dav: DavAccount
    ) {
        val store = MailSession(dav).openImapStore()
        val source = store.getFolder(sourcePath) as IMAPFolder
        source.open(Folder.READ_WRITE)
        source.getMessageByUID(uid)?.let { message ->
            if (targetPath != null) {
                source.copyMessages(arrayOf(message), store.getFolder(targetPath))
            }
            message.setFlag(Flags.Flag.DELETED, true)
            source.expunge()
        }
            source.close(false)
        db.messageDao().delete(mailboxId(accountName, sourcePath), uid)
    }

    companion object {
        private const val TAG = "MessageRepository"
        private const val ATTACHMENT_CACHE_DIR = "attachments"
        private const val MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024
    }
}
