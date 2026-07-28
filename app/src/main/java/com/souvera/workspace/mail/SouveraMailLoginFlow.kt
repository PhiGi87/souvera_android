/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.HttpURLConnection.HTTP_NOT_FOUND

/**
 * Wraps souvera_mail's `POST /apps/souvera_mail/app-passwords/login-flow` endpoint.
 *
 * Login Flow v2 hands the app a Nextcloud app-password `X` (kept as the account password for
 * Files/CalDAV/CardDAV). This endpoint mints an ADDITIONAL combined password `Y` that Stalwart
 * (IMAP/SMTP/Sieve) also accepts, used ONLY for the mail client. We deliberately use `/login-flow`
 * (`create()`) and NOT `/upgrade`: `/upgrade` revokes `X`, which would break WebDAV/DAV auth since
 * those keep using `X`. Keeping `X` and `Y` separate means no invalidation and no broken sync.
 * Auth is HTTP Basic with `X`.
 */
object SouveraMailLoginFlow {

    const val ACCOUNT_KEY_STALWART_ID = "souvera_stalwart_id"
    const val ACCOUNT_KEY_MAIL_PASSWORD = "souvera_mail_password"
    private const val DESCRIPTION = "Souvera Android"

    fun fetchCombinedAppPassword(baseUrl: String, username: String, currentAppPassword: String): CombinedAppPassword =
        try {
            request(baseUrl, username, currentAppPassword, useIndexPhp = false)
        } catch (e: HttpFailure) {
            if (e.code == HTTP_NOT_FOUND) {
                request(baseUrl, username, currentAppPassword, useIndexPhp = true)
            } else {
                throw e
            }
        }

    private class HttpFailure(val code: Int, message: String) : Exception(message)

    private fun request(
        baseUrl: String,
        username: String,
        currentAppPassword: String,
        useIndexPhp: Boolean
    ): CombinedAppPassword {
        val path = if (useIndexPhp) "/index.php/apps/souvera_mail" else "/apps/souvera_mail"
        val body = JSONObject().put("description", DESCRIPTION).toString()
        val httpRequest = Request.Builder()
            .url("$baseUrl$path/app-passwords/login-flow")
            .header("Authorization", Credentials.basic(username, currentAppPassword))
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        OkHttpClient().newCall(httpRequest).execute().use { response ->
            val bodyStr = response.body.string()
            if (!response.isSuccessful) throw HttpFailure(response.code, "HTTP ${response.code} - $bodyStr")
            val json = JSONObject(bodyStr)
            return CombinedAppPassword(
                loginName = json.getString("loginName"),
                appPassword = json.getString("appPassword"),
                stalwartId = json.getString("stalwartId")
            )
        }
    }
}
