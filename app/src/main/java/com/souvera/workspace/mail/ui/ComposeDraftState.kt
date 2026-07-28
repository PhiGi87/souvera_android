/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import com.souvera.workspace.mail.model.OutgoingAttachment

/** Mutable, rotation-safe state of the message currently being written. */
@Stable
class ComposeDraftState(initialTo: String = "", initialSubject: String = "") {
    var to by mutableStateOf(initialTo)
    var cc by mutableStateOf("")
    var bcc by mutableStateOf("")
    var subject by mutableStateOf(initialSubject)
    var body by mutableStateOf("")
    var bodyHtml by mutableStateOf("")
    var showCcBcc by mutableStateOf(false)
    var attachments by mutableStateOf(listOf<OutgoingAttachment>())

    /** Message-ID of the mail being replied to, for In-Reply-To/References threading (null = new mail). */
    var inReplyTo by mutableStateOf<String?>(null)

    companion object {
        private const val INDEX_TO = 0
        private const val INDEX_CC = 1
        private const val INDEX_BCC = 2
        private const val INDEX_SUBJECT = 3
        private const val INDEX_BODY = 4
        private const val INDEX_SHOW_CC_BCC = 5
        private const val INDEX_ATTACHMENTS = 6
        private const val INDEX_IN_REPLY_TO = 7
        private const val SEPARATOR_CODE = 31
        private val FIELD_SEPARATOR = SEPARATOR_CODE.toChar()

        val Saver: Saver<ComposeDraftState, Any> = listSaver(
            save = { state ->
                listOf(
                    state.to,
                    state.cc,
                    state.bcc,
                    state.subject,
                    state.body,
                    state.showCcBcc,
                    ArrayList(state.attachments.map { it.encode() }),
                    state.inReplyTo ?: ""
                )
            },
            restore = { values ->
                ComposeDraftState(values[INDEX_TO] as String, values[INDEX_SUBJECT] as String).apply {
                    cc = values[INDEX_CC] as String
                    bcc = values[INDEX_BCC] as String
                    body = values[INDEX_BODY] as String
                    showCcBcc = values[INDEX_SHOW_CC_BCC] as Boolean
                    @Suppress("UNCHECKED_CAST")
                    attachments = (values[INDEX_ATTACHMENTS] as List<String>).map { decodeAttachment(it) }
                    inReplyTo = (values[INDEX_IN_REPLY_TO] as String).ifBlank { null }
                }
            }
        )

        private fun OutgoingAttachment.encode(): String =
            listOf(uri, name, sizeBytes.toString(), mimeType).joinToString(FIELD_SEPARATOR.toString())

        private fun decodeAttachment(encoded: String): OutgoingAttachment {
            val (uri, name, size, mime) = encoded.split(FIELD_SEPARATOR)
            return OutgoingAttachment(uri, name, size.toLong(), mime)
        }
    }
}
