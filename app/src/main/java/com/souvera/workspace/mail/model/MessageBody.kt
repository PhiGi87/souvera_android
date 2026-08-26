/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.model

/**
 * On-demand fetched body of one message. Deliberately not cached in Room —
 * bodies are fetched on demand from the server. [html] is preferred for
 * display; [plainText] is the fallback.
 */
data class MessageBody(val plainText: String?, val html: String?, val attachments: List<AttachmentMeta> = emptyList())
