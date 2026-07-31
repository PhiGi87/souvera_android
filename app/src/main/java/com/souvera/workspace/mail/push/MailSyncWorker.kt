/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Periodic mail sync worker — safety-net fallback when IMAP IDLE drops.
 * Runs every 15 minutes, syncs INBOX for all Souvera accounts, and shows
 * a notification ONLY for truly new messages (UID-based deduplication).
 *
 * This worker does NOT overlap with IMAP IDLE — it serves as a backup
 * when the device kills the foreground service or the connection drops.
 */

package com.souvera.workspace.mail.push

import android.accounts.AccountManager
import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.owncloud.android.R
import com.souvera.workspace.dav.SouveraSyncManager
import com.souvera.workspace.mail.repository.MessageRepository
import java.util.concurrent.TimeUnit

class MailSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val accountManager = AccountManager.get(applicationContext)
        val accountType = applicationContext.getString(R.string.account_type)
        val accounts = accountManager.getAccountsByType(accountType)
        if (accounts.isEmpty()) return Result.success()

        val syncManager = SouveraSyncManager(applicationContext)
        var newCount = 0

        for (account in accounts) {
            try {
                val dav = syncManager.resolve(account) ?: continue
                newCount += syncInboxAndNotify(account.name, dav)
            } catch (e: Exception) {
                Log.w(TAG, "Mail sync failed for ${account.name}: ${e.message}")
            }
        }

        if (newCount > 0) {
            Log.i(TAG, "Periodic sync: $newCount new messages found")
        }
        return Result.success()
    }

    private suspend fun syncInboxAndNotify(accountName: String, dav: com.souvera.workspace.dav.DavAccount): Int {
        val repository = MessageRepository(applicationContext)
        val result = repository.syncMessages(accountName, "INBOX", dav)
        val entities = result.getOrNull() ?: return 0

        val lastUid = IdlePreferences.getLastKnownUid(applicationContext, accountName)
        val newMessages = if (lastUid > 0) entities.filter { it.uid > lastUid } else emptyList()

        if (newMessages.isNotEmpty()) {
            val newest = newMessages.maxByOrNull { it.uid } ?: return 0
            MailPushNotifier.show(
                applicationContext,
                applicationContext.getString(R.string.mail_push_new_title),
                applicationContext.getString(R.string.mail_push_new_body),
                newest.fromDisplayName ?: newest.fromAddress,
                newest.subject,
                newest.snippet?.take(160),
                notificationId = MailPushNotifier.LATEST_ID
            )
        }

        val maxUid = entities.maxOfOrNull { it.uid } ?: lastUid
        if (maxUid > lastUid) {
            IdlePreferences.setLastKnownUid(applicationContext, accountName, maxUid)
        }

        return newMessages.size
    }

    companion object {
        private const val TAG = "MailSyncWorker"
        private const val WORK_NAME = "souvera_mail_periodic_sync"
        private const val INTERVAL_MINUTES = 15L

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<MailSyncWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES // flex interval
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .addTag("souvera_mail_sync")
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.i(TAG, "Periodic mail sync scheduled (${INTERVAL_MINUTES}m interval)")
        }
    }
}
