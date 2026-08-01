/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2020 Chris Narkiewicz <hello@ezaquarii.com>
 * SPDX-FileCopyrightText: 2017 Mario Danic <mario@lovelyhq.com>
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.owncloud.android.services.firebase;

import android.content.Intent;
import android.text.TextUtils;

import com.google.firebase.messaging.Constants.MessageNotificationKeys;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.nextcloud.client.account.UserAccountManager;
import com.nextcloud.client.jobs.BackgroundJobManager;
import com.nextcloud.client.jobs.NotificationWork;
import com.nextcloud.client.preferences.AppPreferences;
import com.owncloud.android.R;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.utils.PushUtils;
import com.souvera.workspace.push.MailPushNotifier;
import com.souvera.workspace.push.SouveraPushRegistrar;

import java.util.Map;

import javax.inject.Inject;

import androidx.annotation.NonNull;
import dagger.android.AndroidInjection;

/**
 * Works only with gplay variant and Google Play Services installed devices.
 */
public class NCFirebaseMessagingService extends FirebaseMessagingService {
    @Inject AppPreferences preferences;
    @Inject UserAccountManager accountManager;
    @Inject BackgroundJobManager backgroundJobManager;

    static final String TAG = "NCFirebaseMessagingService";

    // Firebase Messaging may apparently use two intent extras to specify a notification message.
    //
    // See the following fragments in https://github.com/firebase/firebase-android-sdk/blob/releases/m144_1.release/
    //  firebase-messaging/src/main/java/com/google/firebase/messaging/FirebaseMessagingService.java#L223
    //  firebase-messaging/src/main/java/com/google/firebase/messaging/NotificationParams.java#L419
    //  firebase-messaging/src/main/java/com/google/firebase/messaging/Constants.java#L158
    //
    // The "old" key is not exposed in com.google.firebase.messaging.Constants.MessageNotificationKeys,
    // so we need to define it ourselves.
    static final String ENABLE_NOTIFICATION_OLD = MessageNotificationKeys.NOTIFICATION_PREFIX_OLD + "e";
    static final String ENABLE_NOTIFICATION_NEW = MessageNotificationKeys.ENABLE_NOTIFICATION;

    static final String SOUVERA_KEY_TYPE = "type";
    static final String SOUVERA_KEY_TITLE = "title";
    static final String SOUVERA_KEY_BODY = "body";

    @Override
    public void onCreate() {
        super.onCreate();
        AndroidInjection.inject(this);
    }

    @Override
    public void handleIntent(Intent intent) {
        final android.os.Bundle extras = intent.getExtras();
        Log_OC.d(TAG, "handleIntent - extras: " +
            ENABLE_NOTIFICATION_NEW + ": " + (extras != null ? extras.getString(ENABLE_NOTIFICATION_NEW) : "null") + ", " +
            ENABLE_NOTIFICATION_OLD + ": " + (extras != null ? extras.getString(ENABLE_NOTIFICATION_OLD) : "null"));

        // When the app is in background and one of the ENABLE_NOTIFICATION or ENABLE_NOTIFICATION_OLD extras is set
        // to "1" in the intent sent from the FCM system code to the FirebaseMessagingService in the application,
        // the FCM library code that handles the intent DOES NOT invoke the onMessageReceived method.
        // It just displays the notification by itself.
        //
        // In our case the original FCM message contains dummy values "NEW_NOTIFICATION" and we need to get the
        // message in onMessageReceived to decrypt it.
        //
        // So we cheat here a little, by telling the FCM library that the notification flag is not set.
        //
        // Code below depends on implementation details of the firebase-messaging library (Firebase Android SDK).
        // https://github.com/firebase/firebase-android-sdk/tree/master/firebase-messaging

        if (extras != null) {
            extras.remove(ENABLE_NOTIFICATION_OLD);
            extras.remove(ENABLE_NOTIFICATION_NEW);
        }
        intent.removeExtra(ENABLE_NOTIFICATION_OLD);
        intent.removeExtra(ENABLE_NOTIFICATION_NEW);
        intent.putExtra(ENABLE_NOTIFICATION_NEW, "0");

        super.handleIntent(intent);
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        Log_OC.d(TAG, "onMessageReceived");
        final Map<String, String> data = remoteMessage.getData();
        final RemoteMessage.Notification notification = remoteMessage.getNotification();

        // Diagnostic: log the raw data keys so we know exactly what the server sent.
        if (!data.isEmpty()) {
            Log_OC.d(TAG, "onMessageReceived data keys: " + data.keySet());
        } else {
            Log_OC.d(TAG, "onMessageReceived data map is EMPTY");
        }

        final String subject = data.get(NotificationWork.KEY_NOTIFICATION_SUBJECT);
        final String signature = data.get(NotificationWork.KEY_NOTIFICATION_SIGNATURE);

        // Nextcloud push-proxy message: encrypted subject + signature, no notification payload.
        if (subject != null && signature != null) {
            backgroundJobManager.startNotificationJob(subject, signature);
            return;
        }

        // NEW_MAIL / CHAT pushes are displayed here (mail + chat); IMAP IDLE
        // (ImapIdleService) remains an additional instant path, and MailSyncWorker
        // a periodic fallback — showing both is safe because MailPushNotifier
        // deduplicates onto LATEST_ID.
        final String type = data.get(SOUVERA_KEY_TYPE);
        com.souvera.workspace.link.call.CallDebugLog.INSTANCE.attach(this);
        com.souvera.workspace.link.call.CallDebugLog.INSTANCE.log(
            "MailPush", "onMessage type=" + type + " notif=" + (notification != null) + " dataKeys=" + data.keySet());

        // Souvera push with a structured payload: new mail (deep link into the mail
        // detail) or new chat (deep link into the Link chat).
        final boolean isMailPush = "new_mail".equals(type) || "mail".equals(type);
        final boolean isChatPush = "new_chat".equals(type) || "chat".equals(type);
        if (isMailPush || isChatPush) {
            try {
                final String resolvedTitle = notification != null
                    ? notification.getTitle() : data.get(SOUVERA_KEY_TITLE);
                final String resolvedBody = notification != null
                    ? notification.getBody() : data.get(SOUVERA_KEY_BODY);
                final String sender = data.get("sender");
                final String mailSubject = data.get("subject");
                final String preview = data.get("preview");

                String mailboxPath = data.get("mailboxPath");
                if (mailboxPath == null) {
                    mailboxPath = "INBOX";
                }
                final String emailId = data.get("emailId");
                final String chatToken = data.get("token");
                // Stable per-chat id so chat pushes never overwrite the latest
                // mail notification (and vice versa); mail keeps LATEST_ID.
                final Integer chatId = isChatPush && chatToken != null ? chatToken.hashCode() : null;
                final String accountName = accountManager.getAccounts().length > 0
                    ? accountManager.getAccounts()[0].name : null;

                MailPushNotifier.INSTANCE.show(
                    this,
                    resolvedTitle,
                    resolvedBody,
                    sender,
                    mailSubject,
                    preview,
                    isChatPush ? chatId : MailPushNotifier.LATEST_ID,
                    isMailPush ? mailboxPath : null,
                    isMailPush ? emailId : null,
                    isChatPush ? chatToken : null,
                    isChatPush ? MailPushNotifier.Target.LINK : MailPushNotifier.Target.MAIL,
                    accountName
                );
            } catch (Exception e) {
                Log_OC.e(TAG, "Souvera push handler failed", e);
                final String fallbackTitle = notification != null
                    ? notification.getTitle()
                    : (data.get(SOUVERA_KEY_TITLE) != null
                        ? data.get(SOUVERA_KEY_TITLE) : getString(R.string.mail_push_new_title));
                final String fallbackBody = notification != null
                    ? notification.getBody()
                    : (data.get(SOUVERA_KEY_BODY) != null
                        ? data.get(SOUVERA_KEY_BODY) : getString(R.string.mail_push_new_body));
                MailPushNotifier.INSTANCE.show(this,
                    fallbackTitle, fallbackBody, null, null, null);
            }
            return;
        }

        // Non-mail Souvera push (test, unknown type, or FCM notification payload).
        final boolean isSouveraPush = type != null || notification != null;
        if (isSouveraPush) {
            try {
                final String resolvedTitle = notification != null ? notification.getTitle() : data.get(SOUVERA_KEY_TITLE);
                final String resolvedBody = notification != null ? notification.getBody() : data.get(SOUVERA_KEY_BODY);
                MailPushNotifier.INSTANCE.show(this, resolvedTitle, resolvedBody,
                    data.get("sender"), data.get("subject"), data.get("preview"));
            } catch (Exception e) {
                Log_OC.e(TAG, "Souvera push handler failed", e);
                final String fallbackTitle = notification != null
                    ? notification.getTitle()
                    : (data.get(SOUVERA_KEY_TITLE) != null
                        ? data.get(SOUVERA_KEY_TITLE) : getString(R.string.mail_push_new_title));
                final String fallbackBody = notification != null
                    ? notification.getBody()
                    : (data.get(SOUVERA_KEY_BODY) != null
                        ? data.get(SOUVERA_KEY_BODY) : getString(R.string.mail_push_new_body));
                MailPushNotifier.INSTANCE.show(this,
                    fallbackTitle, fallbackBody, null, null, null);
            }
        }
    }

    private static long safeLong(String value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @Override
    public void onNewToken(@NonNull String newToken) {
        Log_OC.d(TAG, "onNewToken");
        super.onNewToken(newToken);

        if (!TextUtils.isEmpty(getResources().getString(R.string.push_server_url))) {
            preferences.setPushToken(newToken);
            PushUtils.pushRegistrationToServer(accountManager, preferences.getPushToken());
        }

        SouveraPushRegistrar.INSTANCE.register(this, newToken);
    }
}
