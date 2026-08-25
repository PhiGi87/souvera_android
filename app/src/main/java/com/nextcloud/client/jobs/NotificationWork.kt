/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2020 Chris Narkiewicz <hello@ezaquarii.com>
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.nextcloud.client.jobs

import android.Manifest
import android.accounts.AuthenticatorException
import android.accounts.OperationCanceledException
import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.RingtoneManager
import android.text.TextUtils
import android.util.Base64
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.nextcloud.client.account.User
import com.nextcloud.client.account.UserAccountManager
import com.nextcloud.client.integrations.deck.DeckApi
import com.owncloud.android.R
import com.owncloud.android.datamodel.DecryptedPushMessage
import com.owncloud.android.lib.common.OwnCloudClient
import com.owncloud.android.lib.common.OwnCloudClientFactory
import com.owncloud.android.lib.common.OwnCloudClientManagerFactory
import com.owncloud.android.lib.common.operations.RemoteOperation
import com.owncloud.android.lib.common.utils.Log_OC
import com.owncloud.android.lib.resources.notifications.DeleteNotificationRemoteOperation
import com.owncloud.android.lib.resources.notifications.GetNotificationRemoteOperation
import com.owncloud.android.lib.resources.notifications.models.Notification
import com.owncloud.android.ui.activity.FileDisplayActivity
import com.owncloud.android.ui.navigation.NavigatorActivity
import com.owncloud.android.ui.navigation.NavigatorScreen
import com.owncloud.android.ui.notifications.NotificationUtils
import com.owncloud.android.utils.PushUtils
import com.owncloud.android.utils.theme.ViewThemeUtils
import dagger.android.AndroidInjection
import org.apache.commons.httpclient.HttpMethod
import org.apache.commons.httpclient.HttpStatus
import org.apache.commons.httpclient.methods.DeleteMethod
import org.apache.commons.httpclient.methods.GetMethod
import org.apache.commons.httpclient.methods.PutMethod
import org.apache.commons.httpclient.methods.Utf8PostMethod
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.PrivateKey
import java.security.SecureRandom
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.inject.Inject

@Suppress("LongParameterList")
class NotificationWork constructor(
    private val context: Context,
    params: WorkerParameters,
    private val notificationManager: NotificationManager,
    private val accountManager: UserAccountManager,
    private val deckApi: DeckApi,
    private val viewThemeUtils: ViewThemeUtils
) : Worker(context, params) {

    companion object {
        const val TAG = "NotificationJob"
        const val KEY_NOTIFICATION_ACCOUNT = "KEY_NOTIFICATION_ACCOUNT"
        private const val LINK_CALL_CHANNEL = "souvera_link_call"
        private const val DECLINE_REQUEST_OFFSET = 900_000
        private const val ANSWER_REQUEST_OFFSET = 1000000
        private const val RING_TIMEOUT_MS = 35000L
        const val KEY_NOTIFICATION_SUBJECT = "subject"
        const val KEY_NOTIFICATION_SIGNATURE = "signature"
        private const val KEY_NOTIFICATION_ACTION_LINK = "KEY_NOTIFICATION_ACTION_LINK"
        private const val KEY_NOTIFICATION_ACTION_TYPE = "KEY_NOTIFICATION_ACTION_TYPE"
        private const val PUSH_NOTIFICATION_ID = "PUSH_NOTIFICATION_ID"
        private const val NUMERIC_NOTIFICATION_ID = "NUMERIC_NOTIFICATION_ID"
    }

    @Suppress("TooGenericExceptionCaught", "NestedBlockDepth", "ComplexMethod", "LongMethod") // legacy code
    override fun doWork(): Result {
        // Expedited Work braucht ab Android 12 eine Foreground-Notification,
        // sonst beendet das System den Job, bevor die Anruf-Notification
        // gebaut ist.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            try {
                val fgNotification = NotificationCompat.Builder(context, NotificationUtils.NOTIFICATION_CHANNEL_GENERAL)
                    .setSmallIcon(R.drawable.notification_icon)
                    .setContentTitle(context.getString(R.string.app_name))
                    .setOngoing(true)
                    .setSilent(true)
                    .build()
                setForegroundAsync(
                    com.nextcloud.utils.ForegroundServiceHelper.createWorkerForegroundInfo(
                        47110817,
                        fgNotification,
                        com.owncloud.android.datamodel.ForegroundServiceType.DataSync
                    )
                )
            } catch (_: Exception) {
                Log_OC.d(TAG, "setForegroundAsync failed — continuing anyway")
            }
        }
        val subject = inputData.getString(KEY_NOTIFICATION_SUBJECT) ?: ""
        val signature = inputData.getString(KEY_NOTIFICATION_SIGNATURE) ?: ""
        if (!TextUtils.isEmpty(subject) && !TextUtils.isEmpty(signature)) {
            try {
                val base64DecodedSubject = Base64.decode(subject, Base64.DEFAULT)
                val base64DecodedSignature = Base64.decode(signature, Base64.DEFAULT)
                val privateKey = PushUtils.readKeyFromFile(false) as PrivateKey
                try {
                    val signatureVerification = PushUtils.verifySignature(
                        context,
                        accountManager,
                        base64DecodedSignature,
                        base64DecodedSubject
                    )
                    if (signatureVerification != null && signatureVerification.signatureValid) {
                        val decryptedSubject = decryptSubject(privateKey, base64DecodedSubject)
                        val gson = Gson()
                        val decryptedPushMessage = gson.fromJson(
                            String(decryptedSubject),
                            DecryptedPushMessage::class.java
                        )
                        com.souvera.workspace.link.call.CallDebugLog.attach(context)
                        com.souvera.workspace.link.call.CallDebugLog.log(
                            "LinkPush",
                            "decrypted app=${decryptedPushMessage.app} type=${decryptedPushMessage.type}"
                        )
                        if (decryptedPushMessage.delete) {
                            val missedCaller = com.souvera.workspace.link.call.LinkCallNotifications
                                .consumeForMissed(context, decryptedPushMessage.nid)
                            if (missedCaller != null) {
                                showMissedLinkCall(decryptedPushMessage.nid, missedCaller)
                            } else {
                                notificationManager.cancel(decryptedPushMessage.nid)
                            }
                        } else if (decryptedPushMessage.deleteAll) {
                            notificationManager.cancelAll()
                        } else if (decryptedPushMessage.app == "spreed" && decryptedPushMessage.type == "call") {
                            showIncomingLinkCall(decryptedPushMessage)
                        } else {
                            val user = accountManager.getUser(signatureVerification.account?.name)
                                .orElseThrow { RuntimeException() }
                            fetchCompleteNotification(user, decryptedPushMessage)
                        }
                    }
                } catch (e1: GeneralSecurityException) {
                    Log_OC.d(TAG, "Error decrypting message ${e1.javaClass.name} ${e1.localizedMessage}")
                }
            } catch (exception: Exception) {
                Log_OC.d(TAG, "Something went very wrong" + exception.localizedMessage)
            }
        }
        return Result.success()
    }

    private fun decryptSubject(privateKey: PrivateKey, base64DecodedSubject: ByteArray): ByteArray = try {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        cipher.doFinal(base64DecodedSubject)
    } catch (e: BadPaddingException) {
        Log_OC.e(TAG, "OAEP padding failed, trying PKCS1 for compatibility", e)
        val cipher = Cipher.getInstance("RSA/None/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        cipher.doFinal(base64DecodedSubject)
    }

    @Suppress("LongMethod") // legacy code
    private fun sendNotification(notification: Notification, user: User) {
        // A later Talk notification for the same conversation (e.g. the "missed call" the server
        // posts after the caller hangs up) means the call is over — clear the still-ringing incoming
        // call notification so it does not linger next to it.
        if (notification.app == "spreed") {
            val ringNid = com.souvera.workspace.link.call.LinkCallNotifications
                .ringNidForRoom(context, notification.objectId)
            if (ringNid != 0) {
                notificationManager.cancel(ringNid)
                com.souvera.workspace.link.call.LinkCallNotifications
                    .consumeForMissed(context, ringNid)
                com.souvera.workspace.link.call.LinkCallNotifications.clearRoom(context, notification.objectId)
            }
        }
        val randomId = SecureRandom()
        val file = notification.subjectRichParameters["file"]

        val deckActionOverrideIntent = deckApi.createForwardToDeckActionIntent(notification, user)

        val pendingIntent: PendingIntent?
        if (deckActionOverrideIntent.isPresent) {
            pendingIntent = deckActionOverrideIntent.get()
        } else {
            val intent: Intent
            if (notification.app == "spreed") {
                // Souvera Link: Talk-Chat-Nachricht oeffnet direkt den Chat
                // (nicht die generische Benachrichtigungsliste).
                intent = Intent(context, com.souvera.workspace.link.ui.LinkActivity::class.java)
                intent.putExtra(
                    com.souvera.workspace.push.MailPushNotifier.EXTRA_ACCOUNT_NAME,
                    user.accountName,
                )
                intent.putExtra(
                    com.souvera.workspace.push.MailPushNotifier.EXTRA_CHAT_TOKEN,
                    notification.objectId,
                )
                intent.putExtra(Intent.EXTRA_TITLE, notification.subject)
            } else if (file == null) {
                intent = NavigatorActivity.intent(context, NavigatorScreen.Notifications)
            } else {
                intent = Intent(context, FileDisplayActivity::class.java)
                intent.action = Intent.ACTION_VIEW
                intent.putExtra(FileDisplayActivity.KEY_FILE_ID, file.id)
            }
            intent.putExtra(KEY_NOTIFICATION_ACCOUNT, user.accountName)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            pendingIntent = PendingIntent.getActivity(
                context,
                notification.getNotificationId(),
                intent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val pushNotificationId = randomId.nextInt()
        val notificationBuilder = NotificationCompat.Builder(context, NotificationUtils.NOTIFICATION_CHANNEL_PUSH)
            .setSmallIcon(R.drawable.notification_icon)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.drawable.notification_icon))
            .setShowWhen(true)
            .setSubText(user.accountName)
            .setContentTitle(notification.getSubject())
            .setContentText(notification.getMessage())
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pendingIntent)

        viewThemeUtils.androidx.themeNotificationCompatBuilder(context, notificationBuilder)

        // Remove
        if (notification.getActions().isEmpty()) {
            val disableDetection = Intent(context, NotificationReceiver::class.java)
            disableDetection.putExtra(NUMERIC_NOTIFICATION_ID, notification.getNotificationId())
            disableDetection.putExtra(PUSH_NOTIFICATION_ID, pushNotificationId)
            disableDetection.putExtra(KEY_NOTIFICATION_ACCOUNT, user.accountName)
            val disableIntent = PendingIntent.getBroadcast(
                context,
                pushNotificationId,
                disableDetection,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            notificationBuilder.addAction(
                NotificationCompat.Action(
                    R.drawable.ic_close,
                    context.getString(R.string.remove_push_notification),
                    disableIntent
                )
            )
        } else {
            // Actions
            for (action in notification.getActions()) {
                val actionIntent = Intent(context, NotificationReceiver::class.java)
                actionIntent.putExtra(NUMERIC_NOTIFICATION_ID, notification.getNotificationId())
                actionIntent.putExtra(PUSH_NOTIFICATION_ID, pushNotificationId)
                actionIntent.putExtra(KEY_NOTIFICATION_ACCOUNT, user.accountName)
                actionIntent.putExtra(KEY_NOTIFICATION_ACTION_LINK, action.link)
                actionIntent.putExtra(KEY_NOTIFICATION_ACTION_TYPE, action.type)
                val actionPendingIntent = PendingIntent.getBroadcast(
                    context,
                    randomId.nextInt(),
                    actionIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                var icon: Int
                icon = if (action.primary) {
                    R.drawable.ic_check_circle
                } else {
                    R.drawable.ic_check_circle_outline
                }
                notificationBuilder.addAction(NotificationCompat.Action(icon, action.label, actionPendingIntent))
            }
        }
        applyLinkChatMessaging(notificationBuilder, notification, user)
        notificationBuilder.setPublicVersion(
            NotificationCompat.Builder(context, NotificationUtils.NOTIFICATION_CHANNEL_PUSH)
                .setSmallIcon(R.drawable.notification_icon)
                .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.drawable.notification_icon))
                .setShowWhen(true)
                .setSubText(user.accountName)
                .setContentTitle(context.getString(R.string.new_notification))
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(pendingIntent)
                .also {
                    viewThemeUtils.androidx.themeNotificationCompatBuilder(context, it)
                }
                .build()
        )

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log_OC.w(this, "Missing permission to post notifications")
        } else {
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.notify(notification.getNotificationId(), notificationBuilder.build())
        }
    }

    @Suppress("TooGenericExceptionCaught") // legacy code
    // Souvera "Link" (Talk) incoming call: show a full-screen, high-priority CATEGORY_CALL
    // notification that opens Link so the user can join the ringing conversation. Kept separate
    // from the normal notification path so it never affects message notifications.
    private fun showIncomingLinkCall(message: DecryptedPushMessage) {
        Log_OC.d(TAG, "Incoming Link call push received")
        com.souvera.workspace.link.call.CallDebugLog.attach(context)
        com.souvera.workspace.link.call.CallDebugLog.log(
            "LinkPush",
            "incoming call push nid=${message.nid} room=${message.id}"
        )
        val canFullScreen = android.os.Build.VERSION.SDK_INT <
            android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            notificationManager.canUseFullScreenIntent()
        com.souvera.workspace.link.call.CallDebugLog.log(
            "LinkPush",
            "fullScreenIntent available=$canFullScreen"
        )
        com.souvera.workspace.link.call.LinkCallNotifications.storeFullScreenIntentAllowed(context, canFullScreen)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val existingChannel = notificationManager.getNotificationChannel(LINK_CALL_CHANNEL)
            if (existingChannel != null) {
                com.souvera.workspace.link.call.CallDebugLog.log(
                    "LinkPush",
                    "call channel importance=${existingChannel.importance}"
                )
            }
        }
        val ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val attributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val channel = android.app.NotificationChannel(
                LINK_CALL_CHANNEL,
                context.getString(R.string.link_incoming_call),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(ringtone, attributes)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 800, 800, 800)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
        // Full-screen intent opens a RINGING screen (Accept/Decline) — it must NOT auto-join. The
        // CallStyle "Answer" action joins directly (the user explicitly accepted).
        fun callIntent(incoming: Boolean) = Intent(context, com.souvera.workspace.link.call.CallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(com.souvera.workspace.link.call.CallActivity.EXTRA_TOKEN, message.id)
            .putExtra(com.souvera.workspace.link.call.CallActivity.EXTRA_TITLE, message.subject)
            .putExtra(com.souvera.workspace.link.call.CallActivity.EXTRA_VIDEO, false)
            .putExtra(com.souvera.workspace.link.call.CallActivity.EXTRA_NID, message.nid)
            .putExtra(com.souvera.workspace.link.call.CallActivity.EXTRA_INCOMING, incoming)
        val ringing = PendingIntent.getActivity(
            context,
            message.nid,
            callIntent(true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val answer = PendingIntent.getActivity(
            context,
            message.nid + ANSWER_REQUEST_OFFSET,
            callIntent(false),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Decline beendet den Anruf fuer BEIDE Seiten: CallActivity erhaelt
        // EXTRA_DECLINE und fuehrt einen kurzen Join+Hangup aus (der Server
        // signalisiert dem Anrufer dann das Ende).
        val declineIntent = Intent(context, com.souvera.workspace.link.call.CallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(com.souvera.workspace.link.call.CallActivity.EXTRA_TOKEN, message.id)
            .putExtra(com.souvera.workspace.link.call.CallActivity.EXTRA_TITLE, message.subject)
            .putExtra(com.souvera.workspace.link.call.CallActivity.EXTRA_NID, message.nid)
            .putExtra(com.souvera.workspace.link.call.CallActivity.EXTRA_INCOMING, true)
            .putExtra(com.souvera.workspace.link.call.CallActivity.EXTRA_DECLINE, true)
        val decline = PendingIntent.getActivity(
            context,
            message.nid + DECLINE_REQUEST_OFFSET,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Endet der Anruf, schickt Talk einen ZWEITEN Call-Push fuer denselben
        // Raum ("You missed a call..."). Wenn wir fuer diesen Raum bereits eine
        // aktive Klingel-Notification haben, ist das das Call-Ende-Signal:
        // Klingeln sofort beenden + stille "Verpasst"-Notification zeigen.
        val existingRingNid = com.souvera.workspace.link.call.LinkCallNotifications
            .ringNidForRoom(context, message.id)
        if (existingRingNid != 0 && existingRingNid != message.nid) {
            com.souvera.workspace.link.call.CallDebugLog.log(
                "LinkPush",
                "call end detected for room=${message.id} (ring nid=$existingRingNid, new nid=${message.nid})"
            )
            notificationManager.cancel(existingRingNid)
            val caller = com.souvera.workspace.link.call.LinkCallNotifications
                .consumeForMissed(context, existingRingNid) ?: message.subject
            com.souvera.workspace.link.call.LinkCallNotifications.clearRoom(context, message.id)
            com.souvera.workspace.link.call.LinkCallNotifications.markEnded(context, message.id)
            com.souvera.workspace.link.call.LinkCallEnd.broadcast(context, message.id)
            com.souvera.workspace.link.call.LinkCallNotifications.showMissed(context, message.nid, caller)
            return
        }

        // Verspaeteter Missed-Call-Push, nachdem der Anruf bereits lokal als
        // beendet verarbeitet wurde — nicht erneut klingeln lassen.
        if (com.souvera.workspace.link.call.LinkCallNotifications.endedRecently(context, message.id)) {
            // Ende-Markierung vorhanden: Entweder ein verspaeteter Missed-Call-Push
            // ODER ein ganz neuer Anruf im selben Raum. Server befragen, damit
            // echte neue Anrufe niemals verschluckt werden.
            val active = runCatching {
                val account = android.accounts.AccountManager.get(context)
                    .getAccountsByType(context.getString(R.string.account_type))
                    .firstOrNull()
                val dav = account?.let { com.souvera.workspace.dav.SouveraSyncManager(context).resolve(it) }
                dav != null && com.souvera.workspace.link.net.OcsApi(dav)
                    .participantInCallFlags(message.id).any { it and 1 != 0 }
            }.getOrDefault(true)
            if (!active) {
                com.souvera.workspace.link.call.CallDebugLog.log(
                    "LinkPush",
                    "late missed-call push, no active call for room=${message.id} — ignoring"
                )
                notificationManager.cancel(message.nid)
                return
            }
            com.souvera.workspace.link.call.CallDebugLog.log(
                "LinkPush",
                "new call for room=${message.id} — clearing ended marker"
            )
            com.souvera.workspace.link.call.LinkCallNotifications.clearEnded(context, message.id)
        }

        com.souvera.workspace.link.call.LinkCallNotifications.markIncoming(
            context,
            message.nid,
            message.subject,
            message.id
        )
        val caller = androidx.core.app.Person.Builder().setName(message.subject).setImportant(true).build()
        val builder = NotificationCompat.Builder(context, LINK_CALL_CHANNEL)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(message.subject)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(ringtone)
            .setVibrate(longArrayOf(0, 800, 800, 800, 800))
            .setOngoing(true)
            .setAutoCancel(true)
            .setTimeoutAfter(RING_TIMEOUT_MS)
            .setContentIntent(ringing)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer))
        // Google-Play-Richtlinie (USE_FULL_SCREEN_INTENT): Vollbild-Intent nur
        // setzen, wenn die Berechtigung gewährt ist — sonst als normale
        // Heads-up-Benachrichtigung anzeigen statt unverankertem Overlay.
        if (canFullScreen) {
            builder.setFullScreenIntent(ringing, true)
        }
        notificationManager.notify(message.nid, builder.build())
        // Ueberwachung: entfernt die Klingel-Notification sofort, sobald der
        // Anrufer auflegt (statt erst nach dem 35s-Timeout).
        com.souvera.workspace.link.call.CallRingReaperWork.schedule(context, message.nid, message.id)
    }

    // Replaces the ringing call notification with a dismissible "missed call" once the call ends
    // unanswered, reusing the same notification id so the ring morphs instead of a second entry.
    private fun showMissedLinkCall(nid: Int, caller: String) {
        val intent = Intent(context, com.souvera.workspace.link.ui.LinkActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context,
            nid,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, LINK_CALL_CHANNEL)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(context.getString(R.string.link_missed_call))
            .setContentText(caller)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(pending)
            .build()
        notificationManager.notify(nid, notification)
    }

    private fun fetchCompleteNotification(account: User, decryptedPushMessage: DecryptedPushMessage) {
        val optionalUser = accountManager.getUser(account.accountName)
        if (!optionalUser.isPresent) {
            Log_OC.e(this, "Account may not be null")
            return
        }
        val user = optionalUser.get()
        try {
            val client = OwnCloudClientFactory.createNextcloudClient(user, context)
            val result = GetNotificationRemoteOperation(decryptedPushMessage.nid)
                .execute(client)
            if (result.isSuccess) {
                val notification = result.resultData
                sendNotification(notification, account)
            }
        } catch (e: Exception) {
            Log_OC.e(this, "Error creating account", e)
        }
    }

    // Turns a Link (spreed) chat-message notification into a MessagingStyle notification with a
    // voice-reply action, which Android Auto reads aloud and lets the user answer by voice. Additive
    // and guarded — for non-chat notifications it changes nothing.
    private fun applyLinkChatMessaging(builder: NotificationCompat.Builder, notification: Notification, user: User) {
        val token = notification.objectId
        val message = notification.message
        if (notification.app != "spreed" || message.isNullOrBlank() || token.isNullOrBlank()) return
        if (notification.objectType != "chat" && notification.objectType != "room") return

        val sender = androidx.core.app.Person.Builder().setName(notification.subject ?: "").build()
        val self = androidx.core.app.Person.Builder().setName(context.getString(R.string.link_you)).build()
        val style = NotificationCompat.MessagingStyle(self)
            .addMessage(message, System.currentTimeMillis(), sender)

        val replyIntent = Intent(context, com.souvera.workspace.link.call.LinkReplyReceiver::class.java)
            .putExtra(com.souvera.workspace.link.call.LinkReplyReceiver.EXTRA_TOKEN, token)
            .putExtra(com.souvera.workspace.link.call.LinkReplyReceiver.EXTRA_NID, notification.getNotificationId())
            .putExtra(com.souvera.workspace.link.call.LinkReplyReceiver.EXTRA_ACCOUNT, user.accountName)
        val replyPending = PendingIntent.getBroadcast(
            context,
            notification.getNotificationId(),
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val remoteInput = androidx.core.app.RemoteInput.Builder(
            com.souvera.workspace.link.call.LinkReplyReceiver.KEY_REPLY
        )
            .setLabel(context.getString(R.string.link_reply))
            .build()
        val replyAction = NotificationCompat.Action.Builder(0, context.getString(R.string.link_reply), replyPending)
            .addRemoteInput(remoteInput)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()

        builder.setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .addAction(replyAction)
    }

    class NotificationReceiver : BroadcastReceiver() {
        private lateinit var accountManager: UserAccountManager

        /**
         * This is a workaround for a Dagger compiler bug - it cannot inject
         * into a nested Kotlin class for some reason, but the helper
         * works.
         */
        @Inject
        fun inject(accountManager: UserAccountManager) {
            this.accountManager = accountManager
        }

        @Suppress("ComplexMethod") // legacy code
        override fun onReceive(context: Context, intent: Intent) {
            AndroidInjection.inject(this, context)
            val numericNotificationId = intent.getIntExtra(NUMERIC_NOTIFICATION_ID, 0)
            val accountName = intent.getStringExtra(KEY_NOTIFICATION_ACCOUNT)
            if (numericNotificationId != 0) {
                Thread(
                    Runnable {
                        val notificationManager = context.getSystemService(
                            Activity.NOTIFICATION_SERVICE
                        ) as NotificationManager
                        var oldNotification: android.app.Notification? = null
                        for (statusBarNotification in notificationManager.activeNotifications) {
                            if (numericNotificationId == statusBarNotification.id) {
                                oldNotification = statusBarNotification.notification
                                break
                            }
                        }
                        cancel(context, numericNotificationId)
                        try {
                            val optionalUser = accountManager.getUser(accountName)
                            if (optionalUser.isPresent) {
                                val user = optionalUser.get()
                                val client = OwnCloudClientManagerFactory.getDefaultSingleton()
                                    .getClientFor(user.toOwnCloudAccount(), context)
                                val nextcloudClient = OwnCloudClientFactory.createNextcloudClient(user, context)
                                val actionType = intent.getStringExtra(KEY_NOTIFICATION_ACTION_TYPE)
                                val actionLink = intent.getStringExtra(KEY_NOTIFICATION_ACTION_LINK)
                                val success: Boolean = if (!actionType.isNullOrEmpty() && !actionLink.isNullOrEmpty()) {
                                    val resultCode = executeAction(actionType, actionLink, client)
                                    resultCode == HttpStatus.SC_OK || resultCode == HttpStatus.SC_ACCEPTED
                                } else {
                                    DeleteNotificationRemoteOperation(numericNotificationId)
                                        .execute(nextcloudClient).isSuccess
                                }
                                if (success) {
                                    if (oldNotification == null) {
                                        cancel(context, numericNotificationId)
                                    }
                                } else {
                                    notificationManager.notify(numericNotificationId, oldNotification)
                                }
                            }
                        } catch (e: IOException) {
                            Log_OC.e(TAG, "Error initializing client", e)
                        } catch (e: OperationCanceledException) {
                            Log_OC.e(TAG, "Error initializing client", e)
                        } catch (e: AuthenticatorException) {
                            Log_OC.e(TAG, "Error initializing client", e)
                        }
                    }
                ).start()
            }
        }

        @Suppress("ReturnCount") // legacy code
        private fun executeAction(actionType: String, actionLink: String, client: OwnCloudClient): Int {
            val method: HttpMethod
            method = when (actionType) {
                "GET" -> GetMethod(actionLink)
                "POST" -> Utf8PostMethod(actionLink)
                "DELETE" -> DeleteMethod(actionLink)
                "PUT" -> PutMethod(actionLink)
                else -> return 0 // do nothing
            }
            method.setRequestHeader(RemoteOperation.OCS_API_HEADER, RemoteOperation.OCS_API_HEADER_VALUE)
            try {
                return client.executeMethod(method)
            } catch (e: IOException) {
                Log_OC.e(TAG, "Execution of notification action failed: $e")
            }
            return 0
        }

        private fun cancel(context: Context, notificationId: Int) {
            val notificationManager = context.getSystemService(Activity.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(notificationId)
        }
    }
}
