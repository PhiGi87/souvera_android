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
import com.souvera.workspace.link.ui.LinkActivity
import com.souvera.workspace.mail.ui.MailActivity

/**
 * Shows the status-bar notification for an incoming "new mail" / "new chat" push. This is the
 * display side of the push pipeline (Stalwart webhook -> souvera_mail -> FCM -> device); it is
 * deliberately free of any Firebase dependency so it lives in the shared source set and the
 * notification looks identical regardless of how it was triggered.
 */
object MailPushNotifier {

    private const val TAG = "MailPushNotifier"

    /** Separate notification channels so mail and chat settings are independent. */
    const val CHANNEL_MAIL_PUSH = "SOUVERA_MAIL_PUSH"
    const val CHANNEL_LINK_PUSH = "SOUVERA_LINK_PUSH"

    /** Group keys for bundling multiple notifications per category. */
    private const val GROUP_MAIL = "souvera_mail_group"
    private const val GROUP_LINK = "souvera_link_group"

    /** Summary notification IDs for each group. */
    private const val MAIL_SUMMARY_ID = 1_000_000
    private const val LINK_SUMMARY_ID = 1_000_002

    const val EXTRA_MAILBOX_PATH = "com.souvera.workspace.push.EXTRA_MAILBOX_PATH"
    const val EXTRA_MAIL_ID = "com.souvera.workspace.push.EXTRA_MAIL_ID"
    const val EXTRA_CHAT_TOKEN = "com.souvera.workspace.push.EXTRA_CHAT_TOKEN"
    const val EXTRA_ACCOUNT_NAME = "com.souvera.workspace.push.EXTRA_ACCOUNT_NAME"

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
        mailId: String? = null,
        chatToken: String? = null,
        target: Target = Target.MAIL,
        accountName: String? = null
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channelId = when (target) {
            Target.MAIL -> CHANNEL_MAIL_PUSH
            Target.LINK -> CHANNEL_LINK_PUSH
        }
        ensureChannel(manager, context, channelId, target)

        val requestCode = when (target) {
            Target.MAIL -> (mailboxPath + "|" + (mailId ?: "0")).hashCode()
            Target.LINK -> (chatToken ?: "").hashCode()
        }

        val activityClass = when (target) {
            Target.MAIL -> MailActivity::class.java
            Target.LINK -> LinkActivity::class.java
        }
        val headline = sender ?: title ?: context.getString(R.string.mail_push_new_title)

        val intent = Intent(context, activityClass)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply {
                if (accountName != null) putExtra(EXTRA_ACCOUNT_NAME, accountName)
                if (mailboxPath != null) putExtra(EXTRA_MAILBOX_PATH, mailboxPath)
                if (mailId != null) putExtra(EXTRA_MAIL_ID, mailId)
                if (chatToken != null) putExtra(EXTRA_CHAT_TOKEN, chatToken)
                if (target == Target.LINK) putExtra(Intent.EXTRA_TITLE, headline)
            }

        val pending = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val summary = subject ?: body ?: context.getString(R.string.mail_push_new_body)
        val expanded = preview?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(subject, body).joinToString("\n").ifBlank { summary }

        val groupKey = when (target) {
            Target.MAIL -> GROUP_MAIL
            Target.LINK -> GROUP_LINK
        }

        val category = when (target) {
            Target.MAIL -> NotificationCompat.CATEGORY_EMAIL
            Target.LINK -> NotificationCompat.CATEGORY_MESSAGE
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(headline)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded).setSummaryText(headline))
            .setCategory(category)
            .setGroup(groupKey)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val id = notificationId ?: (headline + summary).hashCode()
        Log.d(TAG, "Showing push notification id=$id target=$target headline=\"$headline\" summary=\"$summary\" channel=$channelId")
        manager.notify(id, notification)
        showGroupSummary(manager, context, target)
    }

    private fun showGroupSummary(manager: NotificationManager, context: Context, target: Target) {
        val channelId = when (target) {
            Target.MAIL -> CHANNEL_MAIL_PUSH
            Target.LINK -> CHANNEL_LINK_PUSH
        }
        val groupKey = when (target) {
            Target.MAIL -> GROUP_MAIL
            Target.LINK -> GROUP_LINK
        }
        val title = when (target) {
            Target.MAIL -> context.getString(R.string.notification_channel_mail_push_name)
            Target.LINK -> context.getString(R.string.notification_channel_link_push_name)
        }
        val summaryId = when (target) {
            Target.MAIL -> MAIL_SUMMARY_ID
            Target.LINK -> LINK_SUMMARY_ID
        }

        val summary = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.mail_push_group_summary))
            .setStyle(NotificationCompat.InboxStyle().setSummaryText(title))
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setGroup(groupKey)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(summaryId, summary)
    }

    /**
     * Creates a push notification channel if Android 8+ and the channel does not already exist.
     * MainApp.notificationChannels() should have created it during Application.onCreate(), but in
     * cold-start-from-push scenarios the process may begin before MainApp finishes initialising.
     */
    private fun ensureChannel(manager: NotificationManager, context: Context, channelId: String, target: Target) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(channelId) != null) return
        Log.w(TAG, "Push channel missing ($channelId) — creating defensively")
        val name = when (target) {
            Target.MAIL -> context.getString(R.string.notification_channel_mail_push_name)
            Target.LINK -> context.getString(R.string.notification_channel_link_push_name)
        }
        val description = when (target) {
            Target.MAIL -> context.getString(R.string.notification_channel_mail_push_description)
            Target.LINK -> context.getString(R.string.notification_channel_link_push_description)
        }
        val channel = NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_HIGH).apply {
            this.description = description
        }
        manager.createNotificationChannel(channel)
    }
}
