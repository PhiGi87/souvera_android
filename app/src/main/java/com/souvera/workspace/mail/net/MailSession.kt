/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.net

import com.souvera.workspace.dav.DavAccount
import com.souvera.workspace.mail.model.LoadedAttachment
import com.souvera.workspace.mail.model.OutgoingMessage
import jakarta.mail.Part
import java.util.Properties
import jakarta.activation.DataHandler
import jakarta.mail.Address
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import org.eclipse.angus.mail.imap.IMAPStore
import org.eclipse.angus.mail.smtp.SMTPTransport

/**
 * Opens IMAP/SMTP connections for a Souvera account and builds outgoing MIME messages. Auth is
 * always the combined app-password [com.souvera.workspace.mail.SouveraMailCredentialManager]
 * provisions.
 *
 * IMAP stores are cached per account so consecutive sync/refresh operations reuse the warmed-up
 * connection — the first open pays the TCP+TLS handshake; subsequent calls return the cached store
 * instantly. SMTP transports are lightweight and remain per-send.
 */
class MailSession(private val dav: DavAccount) {

    private val host: String = dav.baseUrl
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
        .substringBefore(':')

    fun openImapStore(): IMAPStore {
        val store = storeCache
        if (store != null && store.isConnected) return store
        val fresh = rawImapStore()
        storeCache = fresh
        return fresh
    }

    /** Opens a brand-new IMAP store (ignores cache). Used deliberately in contexts that expect a
     *  fresh connection (e.g. the push-content fetcher running on a transient thread). */
    fun openImapStoreFresh(): IMAPStore = rawImapStore()

    fun openSmtpTransport(): SMTPTransport {
        val transport = smtpSession().getTransport("smtp") as SMTPTransport
        transport.connect(host, SMTP_PORT, dav.username, dav.password)
        return transport
    }

    fun buildMessage(fromAddress: String, outgoing: OutgoingMessage, attachments: List<LoadedAttachment>): MimeMessage =
        MimeMessage(smtpSession()).apply {
            setFrom(InternetAddress(fromAddress))
            setRecipients(Message.RecipientType.TO, toAddresses(outgoing.to))
            if (outgoing.cc.isNotEmpty()) setRecipients(Message.RecipientType.CC, toAddresses(outgoing.cc))
            if (outgoing.bcc.isNotEmpty()) setRecipients(Message.RecipientType.BCC, toAddresses(outgoing.bcc))
            subject = outgoing.subject
            outgoing.inReplyTo?.takeIf { it.isNotBlank() }?.let { id ->
                setHeader("In-Reply-To", id)
                setHeader("References", id)
            }
            setContent(buildContent(outgoing.body, outgoing.bodyHtml, attachments))
        }

    /** Re-validates a cached IMAP store with a short no-op command. If the server is unreachable
     *  returns false so the caller can reconnect. */
    fun isReallyConnected(): Boolean = runCatching {
        storeCache?.isConnected == true && storeCache?.let { s -> s.hasCapability("IMAP4rev1"); true } == true
    }.getOrDefault(false)

    private fun rawImapStore(): IMAPStore {
        val props = Properties().apply {
            setProperty("mail.imap.ssl.enable", "true")
            setProperty("mail.imap.auth.mechanisms", "PLAIN LOGIN")
            setProperty("mail.imap.port", IMAP_PORT.toString())
            setProperty("mail.imap.connectiontimeout", CONNECT_TIMEOUT_MS.toString())
            setProperty("mail.imap.timeout", READ_TIMEOUT_MS.toString())
        }
        val store = Session.getInstance(props).getStore("imap") as IMAPStore
        store.connect(host, IMAP_PORT, dav.username, dav.password)
        return store
    }

    private fun buildContent(body: String, bodyHtml: String, attachments: List<LoadedAttachment>): MimeMultipart {
        val html = bodyHtml.ifBlank { MailMarkup.toHtml(body) }
        val alternative = MimeMultipart("alternative").apply {
            addBodyPart(MimeBodyPart().apply { setText(body, CHARSET_UTF8) })
            addBodyPart(
                MimeBodyPart().apply {
                    setContent(html, "text/html; charset=$CHARSET_UTF8")
                }
            )
        }
        if (attachments.isEmpty()) return alternative
        return MimeMultipart("mixed").apply {
            addBodyPart(MimeBodyPart().apply { setContent(alternative) })
            attachments.forEach { attachment ->
                addBodyPart(
                    MimeBodyPart().apply {
                        dataHandler = DataHandler(ByteArrayDataSource(attachment.bytes, attachment.mimeType))
                        fileName = attachment.name
                        disposition = Part.ATTACHMENT
                    }
                )
            }
        }
    }

    private fun smtpSession(): Session {
        val props = Properties().apply {
            setProperty("mail.smtp.ssl.enable", "true")
            setProperty("mail.smtp.auth.mechanisms", "PLAIN LOGIN")
            setProperty("mail.smtp.port", SMTP_PORT.toString())
            setProperty("mail.smtp.connectiontimeout", CONNECT_TIMEOUT_MS.toString())
            setProperty("mail.smtp.timeout", READ_TIMEOUT_MS.toString())
        }
        return Session.getInstance(props)
    }

    private fun toAddresses(values: List<String>): Array<Address> = values.map { InternetAddress(it) }.toTypedArray()

    companion object {
        private const val TAG = "MailSession"
        private const val IMAP_PORT = 993
        private const val SMTP_PORT = 465
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val CHARSET_UTF8 = "utf-8"

        private var storeCache: IMAPStore? = null
    }
}
