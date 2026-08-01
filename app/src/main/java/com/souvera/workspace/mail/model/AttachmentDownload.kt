/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.model

import java.io.File

/** A downloaded attachment ready to be opened: the cached file plus its MIME type. */
class AttachmentDownload(val file: File, val mimeType: String)
