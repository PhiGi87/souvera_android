/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * IMAP IDLE connection — opens a persistent IMAP connection on the INBOX,
 * issues the IDLE command, and waits for EXISTS untagged responses. Each
 * EXISTS triggers a quick fetch of the newest message for notification
 * enrichment. Runs on a dedicated thread per account.
 */

package com.souvera.workspace.mail.push

import android.content.Context
import android.util.Log
import com.souvera.workspace.dav.DavAccount
import com.souvera.workspace.mail.net.MailSession
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.UIDFolder
import jakarta.mail.event.ConnectionEvent
import jakarta.mail.event.ConnectionListener
import jakarta.mail.event.MessageCountEvent
import jakarta.mail.event.MessageCountListener
import jakarta.mail.internet.InternetAddress
import org.eclipse.angus.mail.imap.IMAPFolder
import java.util.Properties
import jakarta.mail.Session
import org.eclipse.angus.mail.imap.IMAPStore

class ImapIdleConnection(
    private val context: Context,
    private val dav: DavAccount,
    private val onNewMail: (
        sender: String?, subject: String?, snippet: String?,
        mailboxPath: String?, uid: Long?
    ) -> Unit,
    private val onDisconnect: () -> Unit,
) {
    private var thread: Thread? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        thread = Thread({ idleLoop() }, "imap-idle-${dav.username}").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun idleLoop() {
        while (running) {
            try {
                val store = openFreshStore()
                val folder = store.getFolder("INBOX") as IMAPFolder
                folder.open(Folder.READ_ONLY)

                folder.addMessageCountListener(object : MessageCountListener {
                    override fun messagesAdded(e: MessageCountEvent) {
                        handleNewMessages(folder, e.messages)
                    }
                    override fun messagesRemoved(e: MessageCountEvent) {}
                })

                store.addConnectionListener(object : ConnectionListener {
                    override fun opened(e: ConnectionEvent) {}
                    override fun disconnected(e: ConnectionEvent) {
                        Log.w(TAG, "Connection disconnected for ${dav.username}")
                        Thread { onDisconnect() }.start()
                    }
                    override fun closed(e: ConnectionEvent) {
                        Log.w(TAG, "Connection closed for ${dav.username}")
                        Thread { onDisconnect() }.start()
                    }
                })

                Log.i(TAG, "IDLE active for ${dav.username}")
                while (running) {
                    // IDLE blocks until a response arrives or timeout
                    try {
                        folder.idle(false) // false = don't keep alive on timeout
                    } catch (e: jakarta.mail.FolderClosedException) {
                        Log.w(TAG, "IDLE folder closed for ${dav.username}")
                        break
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "IDLE illegal state for ${dav.username}: ${e.message}")
                        break
                    }
                }

                try { folder.close(false) } catch (_: Exception) {}
                try { store.close() } catch (_: Exception) {}
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "IDLE loop error for ${dav.username}: ${e.message}, retrying in ${RECONNECT_BACKOFF_MS}ms")
                    try { Thread.sleep(RECONNECT_BACKOFF_MS) } catch (_: InterruptedException) {}
                }
            }
        }
    }

    private fun handleNewMessages(folder: IMAPFolder, messages: Array<Message>) {
        try {
            val newest = messages.maxByOrNull { it.messageNumber } ?: return
            val uid = runCatching { (folder as UIDFolder).getUID(newest) }.getOrNull()?.takeIf { it > 0 }
            val sender = runCatching {
                (newest.from?.firstOrNull() as? InternetAddress)?.personal
                    ?: (newest.from?.firstOrNull() as? InternetAddress)?.address
            }.getOrNull()
            val subject = runCatching { newest.subject }.getOrNull()
            val snippet = runCatching {
                if (newest is jakarta.mail.internet.MimeMessage) {
                    val content = newest.content
                    if (content is jakarta.mail.internet.MimeMultipart) {
                        val textPart = (0 until content.count)
                            .mapNotNull { content.getBodyPart(it) }
                            .firstOrNull { it.isMimeType("text/plain") || it.isMimeType("text/html") }
                        textPart?.content?.toString()
                            ?.replace(Regex("<[^>]+>"), "")
                            ?.replace(Regex("\\s+"), " ")
                            ?.trim()
                            ?.take(MAX_SNIPPET_LENGTH)
                    } else {
                        content?.toString()?.take(MAX_SNIPPET_LENGTH)
                    }
                } else null
            }.getOrNull()

            Log.d(TAG, "EXISTS for ${dav.username}: sender=$sender subject=$subject uid=$uid")
            val mailboxPath = runCatching { folder.fullName }.getOrNull() ?: "INBOX"
            onNewMail(sender, subject, snippet, mailboxPath, uid)
        } catch (e: Exception) {
            Log.w(TAG, "Handle new messages failed for ${dav.username}: ${e.message}")
            // Still notify — even without enrichment the user should know
            onNewMail(null, null, null, null, null)
        }
    }

    private fun openFreshStore(): IMAPStore {
        val host = dav.baseUrl
            .removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore(':')
        val props = Properties().apply {
            setProperty("mail.imap.ssl.enable", "true")
            setProperty("mail.imap.auth.mechanisms", "PLAIN LOGIN")
            setProperty("mail.imap.port", IMAP_PORT.toString())
            setProperty("mail.imap.connectiontimeout", "8000")
            setProperty("mail.imap.timeout", "60000")
        }
        val store = Session.getInstance(props).getStore("imap") as IMAPStore
        store.connect(host, IMAP_PORT, dav.username, dav.password)
        return store
    }

    companion object {
        private const val TAG = "ImapIdleConnection"
        private const val IMAP_PORT = 993
        private const val RECONNECT_BACKOFF_MS = 10_000L
        private const val MAX_SNIPPET_LENGTH = 160
    }
}
