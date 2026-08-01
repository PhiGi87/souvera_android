/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Local cache of one JMAP mailbox. [id] is `accountName:path`, unique per account. */
@Entity(tableName = "mailboxes")
data class MailboxEntity(
    @PrimaryKey val id: String,
    val accountName: String,
    val name: String,
    val path: String,
    val kind: MailboxKind,
    val namespaceType: NamespaceType = NamespaceType.PERSONAL,
    val ownerIdentity: String? = null,
    val unreadCount: Int,
    val messageCount: Int,
    val jmapId: String? = null,
    val role: String? = null,
    val state: String? = null
)
