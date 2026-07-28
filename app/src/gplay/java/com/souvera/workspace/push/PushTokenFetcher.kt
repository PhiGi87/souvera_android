/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.push

import android.content.Context
import android.text.TextUtils
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.owncloud.android.MainApp
import com.owncloud.android.R
import com.owncloud.android.datamodel.ArbitraryDataProviderImpl
import com.owncloud.android.utils.PushUtils

/**
 * gplay variant: asks Firebase for the current FCM token and registers it BOTH with souvera_mail
 * (mail pushes) and with Nextcloud push v2 (chat/call pushes via the configured proxy). Called when
 * the Mail screen opens so a logged-in device always has an up-to-date registration on the server,
 * even when [com.owncloud.android.services.firebase.NCFirebaseMessagingService.onNewToken] did not
 * fire (e.g. token unchanged across reinstalls). Nextcloud registration does blocking network, so
 * it runs off the main thread.
 */
object PushTokenFetcher {

    private const val TAG = "PushTokenFetcher"

    @Suppress("DEPRECATION")
    fun fetchAndRegister(context: Context, onToken: (String) -> Unit) {
        Log.d(TAG, "Requesting FCM token from Firebase…")
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                Log.i(TAG, "FCM token obtained: len=${token.length} prefix=${token.take(12)}…")
                SouveraPushRegistrar.register(context, token)
                registerWithNextcloud(context, token)
                onToken(token)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "FCM token fetch FAILED — is Play Services available? google-services.json correct?", e)
            }
    }

    private fun registerWithNextcloud(context: Context, token: String) {
        val pushServer = context.getString(R.string.push_server_url)
        if (TextUtils.isEmpty(pushServer) || TextUtils.isEmpty(token)) return
        val app = context.applicationContext as? MainApp ?: return
        val accountManager = app.userAccountManager ?: return
        app.preferences.pushToken = token
        Thread {
            forceReRegistrationOnce(context, accountManager)
            runCatching { PushUtils.pushRegistrationToServer(accountManager, token) }
                .onFailure { Log.w(TAG, "Nextcloud push registration failed", it) }
        }.start()
    }

    // One-time: a stale stored push state (from an in-place update without re-login) makes
    // PushUtils skip registration ("register=false"), even though the push proxy no longer knows
    // the device. Clearing it once forces a fresh Nextcloud + proxy registration.
    private fun forceReRegistrationOnce(
        context: Context,
        accountManager: com.nextcloud.client.account.UserAccountManager
    ) {
        val prefs = context.getSharedPreferences(FORCE_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(FORCE_KEY, false)) return
        val provider = ArbitraryDataProviderImpl(context)
        runCatching {
            accountManager.accounts.forEach { provider.deleteKeyForAccount(it.name, PushUtils.KEY_PUSH) }
        }
        prefs.edit().putBoolean(FORCE_KEY, true).apply()
    }

    private const val FORCE_PREFS = "souvera_push"
    private const val FORCE_KEY = "nc_push_forced_v68"
}
