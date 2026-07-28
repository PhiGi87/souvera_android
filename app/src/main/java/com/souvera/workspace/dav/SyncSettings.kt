/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.dav

import android.accounts.Account
import android.content.ContentResolver
import android.content.Context
import android.provider.CalendarContract
import android.provider.ContactsContract

/**
 * DAVx5-style sync controls for the in-app CalDAV/CardDAV sync: a chosen automatic interval
 * (or manual only), an on-demand "sync now" trigger, and the last-successful-sync timestamp per
 * authority (written by the sync adapters via [recordSync], shown in the settings screen). Backed
 * by the default shared preferences so it lives alongside the per-collection toggles.
 */
class SyncSettings(context: Context) {

    private val prefs =
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

    fun intervalSeconds(): Long = prefs.getString(KEY_INTERVAL, DEFAULT_INTERVAL.toString())
        ?.toLongOrNull() ?: DEFAULT_INTERVAL

    fun recordSync(authority: String) {
        prefs.edit().putLong(KEY_LAST_SYNC_PREFIX + authority, System.currentTimeMillis()).apply()
    }

    fun lastSync(authority: String): Long = prefs.getLong(KEY_LAST_SYNC_PREFIX + authority, 0L)

    /** Applies the stored interval to Android's sync framework for both DAV authorities. */
    fun applyInterval(account: Account) {
        val seconds = intervalSeconds()
        AUTHORITIES.forEach { authority ->
            ContentResolver.setIsSyncable(account, authority, 1)
            if (seconds <= 0L) {
                ContentResolver.setSyncAutomatically(account, authority, false)
                ContentResolver.removePeriodicSync(account, authority, android.os.Bundle.EMPTY)
            } else {
                ContentResolver.setSyncAutomatically(account, authority, true)
                ContentResolver.addPeriodicSync(account, authority, android.os.Bundle.EMPTY, seconds)
            }
        }
    }

    fun requestManualSync(account: Account) {
        val extras = android.os.Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        AUTHORITIES.forEach { authority -> ContentResolver.requestSync(account, authority, extras) }
    }

    companion object {
        const val KEY_INTERVAL = "dav_sync_interval_seconds"
        private const val KEY_LAST_SYNC_PREFIX = "dav_last_sync_"
        private const val DEFAULT_INTERVAL = 3600L
        val AUTHORITIES = listOf(CalendarContract.AUTHORITY, ContactsContract.AUTHORITY)
    }
}
