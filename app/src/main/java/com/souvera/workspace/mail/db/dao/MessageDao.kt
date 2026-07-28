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
import com.souvera.workspace.mail.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE mailboxId = :mailboxId ORDER BY dateSent DESC LIMIT :limit")
    fun observeMessages(mailboxId: String, limit: Int): Flow<List<MessageEntity>>

    @Query(
        "SELECT * FROM messages WHERE accountName = :accountName AND (subject LIKE '%' || :query || '%' " +
            "OR fromAddress LIKE '%' || :query || '%' OR fromDisplayName LIKE '%' || :query || '%') " +
            "ORDER BY dateSent DESC LIMIT :limit"
    )
    fun searchMessages(accountName: String, query: String, limit: Int): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Query("UPDATE messages SET isRead = :isRead WHERE mailboxId = :mailboxId AND uid = :uid")
    suspend fun markRead(mailboxId: String, uid: Long, isRead: Boolean)

    @Query("UPDATE messages SET isFlagged = :isFlagged WHERE mailboxId = :mailboxId AND uid = :uid")
    suspend fun markFlagged(mailboxId: String, uid: Long, isFlagged: Boolean)

    @Query("DELETE FROM messages WHERE mailboxId = :mailboxId AND uid = :uid")
    suspend fun delete(mailboxId: String, uid: Long)

    @Query("DELETE FROM messages WHERE mailboxId = :mailboxId AND uid BETWEEN :minUid AND :maxUid AND uid NOT IN (:fetchedUids)")
    suspend fun deleteMissingInRange(mailboxId: String, minUid: Long, maxUid: Long, fetchedUids: List<Long>)

    @Query("DELETE FROM messages WHERE mailboxId = :mailboxId")
    suspend fun deleteAllInMailbox(mailboxId: String)
}
