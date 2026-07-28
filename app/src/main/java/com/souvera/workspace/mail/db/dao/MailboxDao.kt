/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.souvera.workspace.mail.db.entity.MailboxEntity
import com.souvera.workspace.mail.db.entity.MailboxKind
import kotlinx.coroutines.flow.Flow

@Dao
interface MailboxDao {

    @Query("SELECT * FROM mailboxes WHERE accountName = :accountName ORDER BY name")
    fun observeMailboxes(accountName: String): Flow<List<MailboxEntity>>

    @Query("SELECT * FROM mailboxes WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): MailboxEntity?

    @Query(
        "SELECT * FROM mailboxes WHERE accountName = :accountName AND kind = :kind " +
            "AND namespaceType = 'PERSONAL' LIMIT 1"
    )
    suspend fun findByKind(accountName: String, kind: MailboxKind): MailboxEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(mailboxes: List<MailboxEntity>)

    @Query("DELETE FROM mailboxes WHERE accountName = :accountName AND id NOT IN (:keepIds)")
    suspend fun pruneRemoved(accountName: String, keepIds: List<String>)

    @Query("UPDATE mailboxes SET uidValidity = :uidValidity, lastSeenUid = :lastSeenUid WHERE id = :id")
    suspend fun updateSyncState(id: String, uidValidity: Long, lastSeenUid: Long)

    @Query("SELECT * FROM mailboxes WHERE accountName = :accountName")
    suspend fun getMailboxes(accountName: String): List<MailboxEntity>

    @Query("UPDATE mailboxes SET unreadCount = :unreadCount, messageCount = :messageCount WHERE id = :id")
    suspend fun updateCounts(id: String, unreadCount: Int, messageCount: Int)
}
