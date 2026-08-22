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

    @Query("SELECT * FROM messages WHERE mailboxId = :mailboxId AND emailId = :emailId LIMIT 1")
    suspend fun getByMailboxAndId(mailboxId: String, emailId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE accountName = :accountName AND emailId = :emailId LIMIT 1")
    suspend fun getByAccountAndEmailId(accountName: String, emailId: String): MessageEntity?

    @Query(
        "SELECT * FROM messages WHERE accountName = :accountName AND (subject LIKE '%' || :query || '%' " +
            "OR fromAddress LIKE '%' || :query || '%' OR fromDisplayName LIKE '%' || :query || '%') " +
            "ORDER BY dateSent DESC LIMIT :limit"
    )
    fun searchMessages(accountName: String, query: String, limit: Int): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Query("UPDATE messages SET isRead = :isRead WHERE mailboxId = :mailboxId AND emailId = :emailId")
    suspend fun markRead(mailboxId: String, emailId: String, isRead: Boolean)

    @Query("UPDATE messages SET isFlagged = :isFlagged WHERE mailboxId = :mailboxId AND emailId = :emailId")
    suspend fun markFlagged(mailboxId: String, emailId: String, isFlagged: Boolean)

    @Query("DELETE FROM messages WHERE mailboxId = :mailboxId AND emailId = :emailId")
    suspend fun delete(mailboxId: String, emailId: String)

    @Query("DELETE FROM messages WHERE mailboxId = :mailboxId AND emailId NOT IN (:keptIds)")
    suspend fun deleteMissing(mailboxId: String, keptIds: List<String>)

    @Query("DELETE FROM messages WHERE mailboxId = :mailboxId")
    suspend fun deleteAllInMailbox(mailboxId: String)
}
