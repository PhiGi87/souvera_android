/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Lightweight preferences for the IMAP IDLE subsystem — stores the last
 * known UID per account so the periodic sync worker can detect truly new
 * messages and skip re-notifying already-seen ones.
 */

package com.souvera.workspace.mail.push

import android.content.Context

object IdlePreferences {
    private const val PREFS_NAME = "souvera_idle_prefs"
    private const val KEY_LAST_UID_PREFIX = "last_uid_"

    fun getLastKnownUid(context: Context, accountName: String): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_LAST_UID_PREFIX + accountName, 0L)
    }

    fun setLastKnownUid(context: Context, accountName: String, uid: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_UID_PREFIX + accountName, uid).apply()
    }
}
