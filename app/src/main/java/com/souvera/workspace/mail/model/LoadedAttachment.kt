/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.model

/** An [OutgoingAttachment] with its bytes already read from the content resolver. */
class LoadedAttachment(val name: String, val mimeType: String, val bytes: ByteArray)
