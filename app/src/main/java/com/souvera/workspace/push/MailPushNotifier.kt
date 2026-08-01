/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.owncloud.android.R
import com.owncloud.android.ui.notifications.NotificationUtils
import com.souvera.workspace.link.ui.LinkActivity
import com.souvera.workspace.mail.ui.MailActivity

/**
 * Shows the status-bar notification for an incoming "new mail" / "new chat" push. This is the
 * display side of the push pipeline (Stalwart webhook -> souvera_mail -> FCM -> device); it is
 * deliberately free of any Firebase dependency so it lives in the shared source set and the
 * notification looks identical regardless of how it was triggered. The small icon is the
 * alpha-only Souvera "S" so the workspace, not Nextcloud, appears in the status bar.
 */
object MailPushNotifier {

    private const val TAG = "MailPushNotifier"
    private const val GROUP_KEY = "souvera_mail_group"

    const val EXTRA_MAILBOX_PATH = "com.souvera.workspace.push.EXTRA_MAILBOX_PATH"
    const val EXTRA_MAIL_UID = "com.souvera.workspace.push.EXTRA_MAIL_UID"
    const val EXTRA_CHAT_TOKEN = "com.souvera.workspace.push.EXTRA_CHAT_TOKEN"

    /** Which app screen a push opens on tap. */
    enum class Target { MAIL, LINK }

    /**
     * Notification ID used for the "show generic first, enrich later" pattern.
     * Successive pushes update this same id so the tray shows the most recent.
     */
    const val LATEST_ID: Int = 1_000_001

    @Suppress("LongParameterList")
    @JvmOverloads
    fun show(
        context: Context,
        title: String?,
        body: String?,
        sender: String? = null,
        subject: String? = null,
        preview: String? = null,
        notificationId: Int? = null,
        mailboxPath: String? = null,
        mailUid: Long? = null,
        chatToken: String? = null,
        target: Target = Target.MAIL
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Defense-in-depth: create the push channel if MainApp.notificationChannels() somehow did
        // not run yet (process start from a push before Application.onCreate completes).
        ensureChannel(manager, context)

        // Unique request code per notification target+content so the extras of one push can never
        // be replaced by another push's PendingIntent (same request code + FLAG_UPDATE_CURRENT).
        val requestCode = when (target) {
            Target.MAIL -> (mailboxPath + "|" + (mailUid ?: 0L)).hashCode()
            Target.LINK -> (chatToken ?: "").hashCode()
        }

        val activityClass = when (target) {
            Target.MAIL -> MailActivity::class.java
            Target.LINK -> LinkActivity::class.java
        }
        val intent = Intent(context, activityClass)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply {
                if (mailboxPath != null) putExtra(EXTRA_MAILBOX_PATH, mailboxPath)
                if (mailUid != null) putExtra(EXTRA_MAIL_UID, mailUid)
                if (chatToken != null) putExtra(EXTRA_CHAT_TOKEN, chatToken)
            }
        val pending = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val headline = sender ?: title ?: context.getString(R.string.mail_push_new_title)
        val summary = subject ?: body ?: context.getString(R.string.mail_push_new_body)
        val expanded = preview?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(subject, body).joinToString("\n").ifBlank { summary }

        val notification = NotificationCompat.Builder(context, NotificationUtils.NOTIFICATION_CHANNEL_PUSH)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(headline)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded).setSummaryText(headline))
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setGroup(GROUP_KEY)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val id = notificationId ?: (headline + summary).hashCode()
        Log.d(TAG, "Showing push notification id=$id target=$target headline=\"$headline\" summary=\"$summary\"")
        manager.notify(id, notification)
    }

    /**
     * Creates the push notification channel if Android 8+ and the channel does not already exist.
     * MainApp.notificationChannels() should have created it during Application.onCreate(), but in
     * cold-start-from-push scenarios the process may begin with onCreate → handleIntent →
     * onMessageReceived before MainApp finishes initialising. Without the channel, the notification
     * is silently dropped on API 26+.
     */
    private fun ensureChannel(manager: NotificationManager, context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(NotificationUtils.NOTIFICATION_CHANNEL_PUSH) != null) return
        Log.w(TAG, "Push channel missing — creating defensively")
        val channel = NotificationChannel(
            NotificationUtils.NOTIFICATION_CHANNEL_PUSH,
            context.getString(R.string.notification_channel_push_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notification_channel_push_description)
            enableLights(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }
}
