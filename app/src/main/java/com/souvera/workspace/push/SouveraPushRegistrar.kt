/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.push

import android.accounts.AccountManager
import android.content.Context
import android.util.Base64
import android.util.Log
import com.owncloud.android.R
import com.souvera.workspace.dav.SouveraSyncManager
import org.apache.commons.httpclient.HttpClient
import org.apache.commons.httpclient.HttpStatus
import org.apache.commons.httpclient.methods.PostMethod
import org.apache.commons.httpclient.methods.StringRequestEntity
import org.json.JSONObject

/**
 * Registers this device's FCM token with the souvera_mail server so it can be reached by the
 * push pipeline (Stalwart webhook -> souvera_mail -> FCM -> device). Authenticates with the account
 * app-password over HTTP Basic, exactly like the DAV endpoints. Free of any Firebase dependency so
 * it can live in the shared source set; the gplay-only messaging service calls in with the token.
 */
object SouveraPushRegistrar {

    private const val TAG = "SouveraPushRegistrar"
    private const val ENDPOINT = "/index.php/apps/souvera_mail/devices"
    private const val PLATFORM = "android"
    private const val PREFS = "souvera_push"
    private const val KEY_REGISTERED_TOKEN = "registered_fcm_token"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    fun register(context: Context, token: String) {
        if (token.isBlank()) {
            Log.w(TAG, "Skipping registration: token is blank")
            return
        }
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        Log.i(TAG, "Registering FCM token (len=${token.length}, first-8=${token.take(8)}…)")
        Thread {
            registerBlocking(appContext, prefs, token)
        }.start()
    }

    private fun registerBlocking(context: Context, prefs: android.content.SharedPreferences, token: String) {
        val account = AccountManager.get(context)
            .getAccountsByType(context.getString(R.string.account_type))
            .firstOrNull()
        if (account == null) {
            Log.e(TAG, "Cannot register push token: no Souvera account in AccountManager — user not logged in?")
            return
        }
        val dav = SouveraSyncManager(context).resolve(account)
        if (dav == null) {
            Log.e(TAG, "Cannot register push token: SouveraSyncManager.resolve returned null")
            return
        }

        val url = dav.baseUrl.trimEnd('/') + ENDPOINT
        Log.i(TAG, "POST $url")

        val body = JSONObject().put("fcmToken", token).put("platform", PLATFORM).toString()
        val client = HttpClient()
        client.httpConnectionManager.params.let { p ->
            p.setParameter("http.connection.timeout", CONNECT_TIMEOUT_MS)
            p.setParameter("http.socket.timeout", READ_TIMEOUT_MS)
        }
        val post = PostMethod(url).apply {
            val credential = Base64.encodeToString(
                "${dav.username}:${dav.password}".toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            setRequestHeader("Authorization", "Basic $credential")
            setRequestHeader("OCS-APIRequest", "true")
            setRequestHeader("Accept", "application/json")
            requestEntity = StringRequestEntity(body, "application/json", "UTF-8")
        }
        runCatching {
            val status = client.executeMethod(post)
            val responseBody = post.responseBodyAsString?.take(500).orEmpty()
            if (status == HttpStatus.SC_OK || status == HttpStatus.SC_CREATED) {
                prefs.edit().putString(KEY_REGISTERED_TOKEN, token).apply()
                Log.i(TAG, "FCM token REGISTERED successfully — HTTP $status — server now has this device")
            } else if (status == HttpStatus.SC_UNAUTHORIZED) {
                Log.e(TAG, "Device registration rejected: HTTP 401 — wrong credentials. Body: $responseBody")
            } else if (status == HttpStatus.SC_NOT_FOUND) {
                Log.e(TAG, "Device registration endpoint not found: HTTP 404 — is souvera_mail installed on server? URL: $url")
            } else if (status == HttpStatus.SC_SERVICE_UNAVAILABLE) {
                Log.e(TAG, "Device registration: HTTP 503 — is souvera_mail enabled? stalwart_webhook_secret configured?")
            } else {
                Log.e(TAG, "Device registration failed: HTTP $status. Body: $responseBody")
            }
        }.onFailure { e ->
            val detail = when {
                e.message?.contains("UnknownHost", true) == true -> "DNS error — check server URL and network: ${e.message}"
                e.message?.contains("ConnectException", true) == true -> "Connection refused — server not reachable: ${e.message}"
                e.message?.contains("timeout", true) == true -> "Timeout — server not responding: ${e.message}"
                e.message?.contains("SSL", true) == true -> "SSL error — certificate problem: ${e.message}"
                else -> e.message ?: e.javaClass.simpleName
            }
            Log.e(TAG, "Device registration POST failed: $detail", e)
        }.also {
            post.releaseConnection()
        }
    }
}
