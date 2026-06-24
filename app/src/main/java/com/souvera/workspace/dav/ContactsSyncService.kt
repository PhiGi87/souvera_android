/*
 * Souvera Workspace - Android Client
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.dav

import android.accounts.Account
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.IBinder

/**
 * Sync adapter service bound to the contacts authority. Performs CardDAV sync.
 */
class ContactsSyncService : Service() {
    override fun onCreate() {
        super.onCreate()
        synchronized(lock) {
            if (syncAdapter == null) {
                syncAdapter = ContactsSyncAdapter(applicationContext)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = syncAdapter!!.syncAdapterBinder

    companion object {
        private val lock = Any()
        private var syncAdapter: ContactsSyncAdapter? = null
    }
}

class ContactsSyncAdapter(context: Context) : AbstractThreadedSyncAdapter(context, true) {
    override fun onPerformSync(
        account: Account,
        extras: Bundle,
        authority: String,
        provider: ContentProviderClient,
        syncResult: SyncResult
    ) {
        runCatching { SouveraSyncManager(context).syncContacts(account) }
            .onFailure { syncResult.stats.numIoExceptions++ }
    }
}
