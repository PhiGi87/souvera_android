/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * IMAP IDLE foreground service — holds a persistent IMAP connection with the
 * IDLE command active so the server can push EXISTS responses instantly when
 * new mail arrives. Completely independent of FCM — no Google dependency.
 *
 * Architecture:
 *   - One service instance per process (Android singleton)
 *   - Manages one IDLE connection per DavAccount
 *   - On EXISTS → fetches newest INBOX message → shows notification
 *   - Connection watchdog reconnects on timeout/disconnect
 *   - Foreground notification keeps Android from killing the service
 */

package com.souvera.workspace.mail.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.owncloud.android.R
import android.accounts.AccountManager
import com.souvera.workspace.dav.SouveraSyncManager
import com.souvera.workspace.mail.ui.MailActivity
import com.souvera.workspace.push.MailPushNotifier

class ImapIdleService : Service() {

    private lateinit var wakeLock: PowerManager.WakeLock
    private val connections = mutableMapOf<String, ImapIdleConnection>()
    private val activeAccounts = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:wakelock")
        wakeLock.setReferenceCounted(false)
        ensureChannel()
        MailSyncWorker.schedule(applicationContext)
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call startForeground() promptly after startForegroundService(), before any slow
        // work (account resolution, connection setup) — Android kills the process otherwise
        // (ForegroundServiceDidNotStartInTimeException).
        updateForegroundNotification()
        val accountName = intent?.getStringExtra(EXTRA_ACCOUNT_NAME)
        if (accountName != null) {
            startIdle(accountName)
        } else {
            startAllAccounts()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Service destroying — stopping all IDLE connections")
        connections.values.forEach { it.stop() }
        connections.clear()
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
        }
        super.onDestroy()
    }

    private fun startAllAccounts() {
        val accountManager = AccountManager.get(applicationContext)
        val accountType = applicationContext.getString(R.string.account_type)
        val accounts = accountManager.getAccountsByType(accountType)
        accounts.forEach { startIdle(it.name) }
    }

    private fun startIdle(accountName: String) {
        if (accountName in activeAccounts) return
        val accountManager = AccountManager.get(applicationContext)
        val accountType = applicationContext.getString(R.string.account_type)
        val account = accountManager.getAccountsByType(accountType).firstOrNull { it.name == accountName }
            ?: return
        val syncManager = SouveraSyncManager(applicationContext)
        val dav = syncManager.resolve(account) ?: run {
            Log.w(TAG, "Cannot resolve credentials for $accountName")
            return
        }
        activeAccounts.add(accountName)
        val conn = ImapIdleConnection(
            context = applicationContext,
            dav = dav,
            onNewMail = { sender, subject, snippet ->
                MailPushNotifier.show(
                    applicationContext,
                    title = applicationContext.getString(R.string.mail_push_new_title),
                    body = applicationContext.getString(R.string.mail_push_new_body),
                    sender = sender,
                    subject = subject,
                    preview = snippet,
                    notificationId = MailPushNotifier.LATEST_ID
                )
            },
            onDisconnect = {
                Log.w(TAG, "IDLE disconnected for $accountName, will reconnect")
                connections.remove(accountName)
                activeAccounts.remove(accountName)
                // Reconnect after a short backoff
                Thread {
                    Thread.sleep(IDLE_RECONNECT_DELAY_MS)
                    startIdle(accountName)
                }.start()
            }
        )
        connections[accountName] = conn
        conn.start()
        updateForegroundNotification()
        Log.i(TAG, "IDLE started for $accountName")
    }

    private fun updateForegroundNotification() {
        val intent = Intent(this, MailActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val count = connections.size
        val title = getString(R.string.mail_push_idle_notification_title)
        val text = if (count > 0)
            getString(R.string.mail_push_idle_notification_text, count)
        else
            getString(R.string.mail_push_idle_notification_connecting)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(FOREGROUND_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(FOREGROUND_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.mail_push_idle_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.mail_push_idle_channel_description)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "ImapIdleService"
        private const val CHANNEL_ID = "souvera_mail_idle"
        private const val FOREGROUND_ID = 2_000_001
        private const val EXTRA_ACCOUNT_NAME = "account_name"
        private const val IDLE_RECONNECT_DELAY_MS = 15_000L

        @JvmOverloads
        fun start(context: Context, accountName: String? = null) {
            val intent = Intent(context, ImapIdleService::class.java).apply {
                if (accountName != null) putExtra(EXTRA_ACCOUNT_NAME, accountName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ImapIdleService::class.java))
        }
    }
}
