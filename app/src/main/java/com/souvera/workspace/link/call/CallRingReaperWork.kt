/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.call

import android.accounts.AccountManager
import android.app.NotificationManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.owncloud.android.R
import com.souvera.workspace.dav.SouveraSyncManager
import com.souvera.workspace.link.net.OcsApi
import java.util.concurrent.TimeUnit

/**
 * Ueberwacht eine klingelnde Anruf-Notification: Sobald der Anruf serverseitig
 * nicht mehr aktiv ist (Anrufer hat aufgelegt), wird die Notification sofort
 * entfernt — ohne auf das 35s-Timeout zu warten. Bei Netzfehlern wird NICHT
 * abgebrochen (ein Fehler darf das Klingeln nicht stoppen).
 */
class CallRingReaperWork(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val nid = inputData.getInt(KEY_NID, 0)
        val token = inputData.getString(KEY_TOKEN) ?: return Result.success()
        if (nid == 0) return Result.success()

        val started = inputData.getLong(KEY_STARTED, System.currentTimeMillis())
        if (System.currentTimeMillis() - started > MAX_LIFETIME_MS) {
            return Result.success() // Notification traegt ihr eigenes Timeout
        }

        val account = AccountManager.get(applicationContext)
            .getAccountsByType(applicationContext.getString(R.string.account_type)).firstOrNull()
        val dav = account?.let { SouveraSyncManager(applicationContext).resolve(it) }

        val callActive = if (dav != null) {
            runCatching {
                OcsApi(dav).participantInCallFlags(token).any { (it and 1) != 0 }
            }.getOrDefault(true)
        } else {
            true
        }

        if (!callActive) {
            CallDebugLog.attach(applicationContext)
            CallDebugLog.log(TAG, "call no longer active — dismissing ringing notification nid=$nid")
            (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(nid)
            val caller = LinkCallNotifications.consumeForMissed(applicationContext, nid)
            if (caller != null) {
                LinkCallNotifications.markEnded(applicationContext, token)
                LinkCallNotifications.showMissed(applicationContext, nid, caller)
            }
            LinkCallEnd.broadcast(applicationContext, token)
            return Result.success()
        }

        schedule(applicationContext, nid, token)
        return Result.success()
    }

    companion object {
        private const val TAG = "CallRingReaper"
        const val KEY_NID = "nid"
        const val KEY_TOKEN = "token"
        const val KEY_STARTED = "started_at"
        private const val DELAY_MS = 4_000L
        private const val MAX_LIFETIME_MS = 40_000L

        fun schedule(context: Context, nid: Int, token: String) {
            val data = Data.Builder()
                .putInt(KEY_NID, nid)
                .putString(KEY_TOKEN, token)
                .putLong(KEY_STARTED, System.currentTimeMillis())
                .build()
            val request = OneTimeWorkRequestBuilder<CallRingReaperWork>()
                .setInputData(data)
                .setInitialDelay(DELAY_MS, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "call_ring_$nid",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
