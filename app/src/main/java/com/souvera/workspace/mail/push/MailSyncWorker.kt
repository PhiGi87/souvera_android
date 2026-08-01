/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Periodic mail sync worker — safety-net fallback. Runs every 15 minutes,
 * syncs INBOX for all Souvera accounts via JMAP Email/queryChanges, and
 * shows a notification for recently arrived messages.
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
import com.souvera.workspace.mail.repository.MailResult
import com.souvera.workspace.mail.repository.MessageRepository
import com.souvera.workspace.push.MailPushNotifier
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
        if (result !is MailResult.Success) return 0
        val entities = result.value

        if (entities.isNotEmpty()) {
            val newest = entities.maxByOrNull { it.dateSent }!!
            val newestSender = newest.fromDisplayName ?: newest.fromAddress
            MailPushNotifier.show(
                applicationContext,
                applicationContext.getString(R.string.mail_push_new_title),
                applicationContext.getString(R.string.mail_push_new_body),
                newestSender,
                newest.subject,
                newest.subject,
                notificationId = MailPushNotifier.LATEST_ID,
                mailboxPath = "INBOX",
                mailId = newest.emailId,
                accountName = accountName
            )
        }

        return entities.count()
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
                5, TimeUnit.MINUTES
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
