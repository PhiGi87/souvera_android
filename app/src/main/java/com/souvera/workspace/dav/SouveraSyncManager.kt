/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.dav

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.util.Log
import com.owncloud.android.lib.common.accounts.AccountUtils

/**
 * Holds the resolved connection data for a Souvera/Nextcloud account.
 */
data class DavAccount(val baseUrl: String, val username: String, val password: String)

/**
 * Entry point that wires an Android [Account] to the CardDAV / CalDAV sync logic.
 * Server paths follow the standard Nextcloud DAV layout.
 */
class SouveraSyncManager(private val context: Context) {

    fun resolve(account: Account): DavAccount? {
        val am = AccountManager.get(context)
        val baseUrl = am.getUserData(account, AccountUtils.Constants.KEY_OC_BASE_URL) ?: return null
        val password = am.getPassword(account) ?: return null
        val username = account.name.substringBeforeLast('@')
        if (baseUrl.isBlank() || password.isBlank() || username.isBlank()) return null
        return DavAccount(baseUrl.trimEnd('/'), username, password)
    }

    fun syncCalendars(account: Account) {
        val dav = resolve(account) ?: run {
            Log.w(TAG, "Cannot resolve credentials for ${account.name}")
            return
        }
        val client = DavClient(dav.baseUrl, dav.username, dav.password)
        val home = "${dav.baseUrl}/remote.php/dav/calendars/${dav.username}/"
        CalDavSync(context, account, client).sync(home)
        SyncSettings(context).recordSync(android.provider.CalendarContract.AUTHORITY)
    }

    fun syncContacts(account: Account) {
        val dav = resolve(account) ?: run {
            Log.w(TAG, "Cannot resolve credentials for ${account.name}")
            return
        }
        val client = DavClient(dav.baseUrl, dav.username, dav.password)
        val home = "${dav.baseUrl}/remote.php/dav/addressbooks/users/${dav.username}/"
        CardDavSync(context, account, client).sync(home)
        SyncSettings(context).recordSync(android.provider.ContactsContract.AUTHORITY)
    }

    companion object {
        const val TAG = "SouveraSyncManager"
    }
}
