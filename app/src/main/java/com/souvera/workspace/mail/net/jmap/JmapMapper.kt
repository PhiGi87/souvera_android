/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.net.jmap

import com.souvera.workspace.mail.db.entity.MailboxEntity
import com.souvera.workspace.mail.db.entity.MailboxKind
import com.souvera.workspace.mail.db.entity.MessageEntity
import com.souvera.workspace.mail.db.entity.NamespaceType
import com.souvera.workspace.mail.model.AttachmentMeta
import com.souvera.workspace.mail.model.MessageBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Maps JMAP response objects (from [JmapApi] / [JmapClient]) into the app's
 * domain entities. Pure functions; no side effects.
 */
object JmapMapper {

    /**
     * Maps a JMAP `Mailbox` object to a [MailboxEntity].
     *
     * [accountName] is the Nextcloud AccountManager account name.
     * [path] is the JMAP mailbox name (used as a path-like identifier);
     * the entity `id` is built as `accountName:path`.
     */
    fun mapMailbox(
        accountName: String,
        json: JSONObject,
        path: String? = null
    ): MailboxEntity {
        val name = json.optString("name", path ?: "?")
        val resolvedPath = path ?: name
        val role = json.optString("role", null).takeIf { !it.isNullOrBlank() }
        val id = "$accountName:$resolvedPath"
        val kind = resolveMailboxKind(role, name, resolvedPath)
        val jmapId = json.optString("id", null).takeIf { !it.isNullOrBlank() }

        return MailboxEntity(
            id = id,
            accountName = accountName,
            name = name,
            path = resolvedPath,
            kind = kind,
            unreadCount = json.optInt("unreadEmails", 0),
            messageCount = json.optInt("totalEmails", 0),
            jmapId = jmapId,
            role = role,
            state = null
        )
    }

    /**
     * Maps a JMAP `Email` object to a [MessageEntity].
     */
    fun mapEmail(
        accountName: String,
        mailboxId: String,
        json: JSONObject
    ): MessageEntity {
        val fromList = parseAddresses(json.optJSONArray("from"))
        val toList = parseAddresses(json.optJSONArray("to"))
        val hasAtt = json.optBoolean("hasAttachment", false) ||
            (json.optJSONArray("attachments")?.length() ?: 0) > 0
        val keywords = json.optJSONObject("keywords")?.toString()

        return MessageEntity(
            accountName = accountName,
            mailboxId = mailboxId,
            emailId = json.getString("id"),
            messageId = json.optJSONArray("messageId")?.optString(0),
            subject = json.optString("subject", ""),
            fromAddress = fromList.firstOrNull()?.optString("email") ?: "",
            fromDisplayName = fromList.firstOrNull()?.optString("name"),
            toAddresses = toList.joinToString(", ") { it.optString("email") ?: "" },
            dateSent = parseJmapDate(json.optString("receivedAt")),
            isRead = keywords?.contains("\$seen") == true,
            isFlagged = keywords?.contains("\$flagged") == true,
            hasAttachments = hasAtt,
            sizeBytes = json.optLong("size", 0L),
            blobId = json.optString("blobId", null).takeIf { !it.isNullOrBlank() },
            threadId = json.optString("threadId", null).takeIf { !it.isNullOrBlank() },
            keywords = keywords
        )
    }

    /** Parses a JMAP email into a [MessageBody] (plain, html, attachments). */
    fun mapBody(json: JSONObject): MessageBody {
        val textParts = json.optJSONArray("textBody")
        val htmlParts = json.optJSONArray("htmlBody")
        val attArr = json.optJSONArray("attachments")
        val attachments = mutableListOf<AttachmentMeta>()
        if (attArr != null) {
            for (i in 0 until attArr.length()) {
                val att = attArr.getJSONObject(i)
                attachments.add(AttachmentMeta(
                    name = att.optString("name", att.optString("partId", "attachment")),
                    sizeBytes = att.optLong("size", 0L),
                    mimeType = att.optString("type", "application/octet-stream")
                ))
            }
        }
        return MessageBody(
            plainText = textParts?.optJSONObject(0)?.optString("blobId"),
            html = htmlParts?.optJSONObject(0)?.optString("blobId"),
            attachments = attachments
        )
    }

    fun resolveMailboxKind(
        role: String?,
        name: String,
        path: String
    ): MailboxKind = when {
        role == "inbox" || name.equals("inbox", ignoreCase = true) -> MailboxKind.INBOX
        role == "sent"   || name.equals("sent", ignoreCase = true)   -> MailboxKind.SENT
        role == "drafts" || name.equals("drafts", ignoreCase = true) -> MailboxKind.DRAFTS
        role == "trash"  || name.equals("trash", ignoreCase = true)  -> MailboxKind.TRASH
        role == "junk"   || name.equals("junk", ignoreCase = true) ||
            name.equals("spam", ignoreCase = true) -> MailboxKind.JUNK
        role == "archive" || name.equals("archive", ignoreCase = true) -> MailboxKind.REGULAR
        else -> MailboxKind.REGULAR
    }

    /* ---------- helpers ------------------------------------------------- */

    private fun parseAddresses(arr: org.json.JSONArray?): List<JSONObject> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun parseJmapDate(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            // "2026-08-02T11:06:30Z" → strip Z, then strip tz offset
            val clean = iso.substringBefore('Z')
                .let { it.substringBeforeLast('+') }
                .let { it.substringBeforeLast('-', it.lastIndexOf('T') + 1) }
                .take(19)
            fmt.parse(clean)?.time ?: 0L
        } catch (_: Exception) { 0L }
    }
}
