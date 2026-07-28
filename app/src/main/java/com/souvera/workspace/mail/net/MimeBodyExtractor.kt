/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.net

import com.souvera.workspace.mail.model.AttachmentMeta
import com.souvera.workspace.mail.model.MessageBody
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.MimeUtility

/**
 * Walks a MIME [Part] tree and pulls out the first plain-text and first HTML body parts plus the
 * metadata of every attachment part. Attachment contents are NOT read here - fetch them on demand
 * via [attachmentParts], which returns parts in the same order as [MessageBody.attachments].
 */
object MimeBodyExtractor {

    fun extract(part: Part): MessageBody {
        var plainText: String? = null
        var html: String? = null
        val attachments = mutableListOf<AttachmentMeta>()
        walk(part) { leaf ->
            when {
                isAttachment(leaf) ->
                    attachments += AttachmentMeta(
                        name = decodedName(leaf),
                        sizeBytes = leaf.size.toLong().coerceAtLeast(0L),
                        mimeType = leaf.contentType.substringBefore(';').trim()
                    )

                leaf.isMimeType("text/plain") && plainText == null -> plainText = leaf.content.toString()

                leaf.isMimeType("text/html") && html == null -> html = leaf.content.toString()
            }
        }
        return MessageBody(plainText, html, attachments)
    }

    fun attachmentParts(part: Part): List<Part> {
        val parts = mutableListOf<Part>()
        walk(part) { leaf -> if (isAttachment(leaf)) parts += leaf }
        return parts
    }

    fun decodedName(part: Part): String {
        val raw = part.fileName ?: return DEFAULT_NAME
        return try {
            MimeUtility.decodeText(raw)
        } catch (_: Exception) {
            raw
        }
    }

    private fun walk(part: Part, visit: (Part) -> Unit) {
        if (part.isMimeType("multipart/*")) {
            val multipart = part.content as Multipart
            for (index in 0 until multipart.count) {
                walk(multipart.getBodyPart(index), visit)
            }
        } else {
            visit(part)
        }
    }

    private fun isAttachment(part: Part): Boolean = Part.ATTACHMENT.equals(part.disposition, ignoreCase = true) ||
        (part.fileName != null && !part.isMimeType("text/plain") && !part.isMimeType("text/html"))

    private const val DEFAULT_NAME = "attachment"
}
