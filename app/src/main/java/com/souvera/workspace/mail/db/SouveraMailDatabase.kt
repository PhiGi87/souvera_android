/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.souvera.workspace.mail.db.dao.MailboxDao
import com.souvera.workspace.mail.db.dao.MessageDao
import com.souvera.workspace.mail.db.entity.MailboxEntity
import com.souvera.workspace.mail.db.entity.MessageEntity

/**
 * Own, dedicated database for the mail cache - deliberately not [com.nextcloud.client.database.
 * NextcloudDatabase], to keep mail schema changes isolated from the legacy files/sync engine's
 * migration chain (see plans/cheeky-splashing-lampson.md Phase 3). Purely a cache of
 * server-authoritative IMAP data, safe to wipe/recreate.
 */
@Database(entities = [MailboxEntity::class, MessageEntity::class], version = 3, exportSchema = false)
@TypeConverters(MailTypeConverters::class)
abstract class SouveraMailDatabase : RoomDatabase() {

    abstract fun mailboxDao(): MailboxDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var instance: SouveraMailDatabase? = null

        fun getInstance(context: Context): SouveraMailDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                SouveraMailDatabase::class.java,
                "souvera_mail.db"
            ).fallbackToDestructiveMigration(true).build().also { instance = it }
        }
    }
}
