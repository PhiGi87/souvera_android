/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.model

/** A file the user picked for an outgoing message, referenced by its content URI. */
data class OutgoingAttachment(val uri: String, val name: String, val sizeBytes: Long, val mimeType: String)
