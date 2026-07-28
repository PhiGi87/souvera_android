/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.model

/** One attachment of a received message; the index into the message's attachment list is implicit. */
data class AttachmentMeta(val name: String, val sizeBytes: Long, val mimeType: String)
