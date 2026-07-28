/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.net

import com.souvera.workspace.mail.db.entity.MessageEntity
import jakarta.mail.Flags
import jakarta.mail.Message
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.eclipse.angus.mail.imap.IMAPFolder

/**
 * Maps a [Message] to its local cache row. Callers must batch-fetch envelope+flags first (see
 * [jakarta.mail.FetchProfile]) - reading these getters without it triggers one IMAP round-trip
 * per field per message.
 */
object ImapMessageMapper {

    fun toEntity(accountName: String, mailboxId: String, folder: IMAPFolder, message: Message): MessageEntity {
        val from = message.from?.firstOrNull() as? InternetAddress
        val toAddresses = message.getRecipients(Message.RecipientType.TO)
            ?.filterIsInstance<InternetAddress>()
            ?.joinToString(", ") { it.address }
            .orEmpty()
        return MessageEntity(
            accountName = accountName,
            mailboxId = mailboxId,
            uid = folder.getUID(message),
            messageId = (message as? MimeMessage)?.messageID,
            subject = message.subject.orEmpty(),
            fromAddress = from?.address.orEmpty(),
            fromDisplayName = from?.personal,
            toAddresses = toAddresses,
            dateSent = message.sentDate?.time ?: 0L,
            isRead = message.flags.contains(Flags.Flag.SEEN),
            isFlagged = message.flags.contains(Flags.Flag.FLAGGED),
            // Attachment flag intentionally not derived here: reading contentType would force a
            // per-message BODYSTRUCTURE round-trip (slow inbox). Resolved when opening a message.
            hasAttachments = false,
            sizeBytes = message.size.toLong()
        )
    }
}
