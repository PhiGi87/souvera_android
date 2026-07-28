/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.net

import com.souvera.workspace.mail.db.entity.MessageEntity

/** Composite key used as [com.souvera.workspace.mail.db.entity.MailboxEntity.id]. */
fun mailboxId(accountName: String, path: String): String = "$accountName:$path"

/** Recovers the IMAP folder path from a message's composite [MessageEntity.mailboxId]. */
fun MessageEntity.mailboxPath(): String = mailboxId.removePrefix("$accountName:")
