/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local cache of one message's envelope (not its full body - that's fetched on demand, see
 * plans/cheeky-splashing-lampson.md Phase 3).
 */
@Entity(
    tableName = "messages",
    indices = [Index(value = ["mailboxId", "uid"], unique = true)]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val accountName: String,
    val mailboxId: String,
    val uid: Long,
    val messageId: String?,
    val subject: String,
    val fromAddress: String,
    val fromDisplayName: String?,
    val toAddresses: String,
    val dateSent: Long,
    val isRead: Boolean,
    val isFlagged: Boolean,
    val hasAttachments: Boolean,
    val sizeBytes: Long
)
