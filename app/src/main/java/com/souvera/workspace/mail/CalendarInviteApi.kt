/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail

import com.souvera.workspace.dav.DavAccount
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Details einer Termineinladung aus der souvera_mail API. */
data class CalendarInvite(
    val uid: String,
    val summary: String,
    val location: String,
    val organizer: String,
    val dtstart: String?,
    val dtend: String?,
)

/**
 * Client für die Termineinladungs-Endpoints von souvera_mail
 * (parse/respond) — gleiche Mechanik wie ShieldApi (Basic Auth mit
 * App-Passwort).
 */
class CalendarInviteApi(private val dav: DavAccount) {

    private val base = dav.baseUrl.trimEnd('/') + "/apps/souvera_mail"
    private val credential = Credentials.basic(dav.username, dav.password)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun parse(emailId: String, partId: String): CalendarInvite? = runCatching {
        val url = "$base/api/v2/calendar-invite/parse?emailId=${emailId.urlEncode()}&partId=${partId.urlEncode()}"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", credential)
            .header("Accept", "application/json")
            .build()
        val body = client.newCall(request).execute().use { it.body?.string() } ?: return null
        val invite = JSONObject(body).optJSONObject("invite") ?: return null
        CalendarInvite(
            uid = invite.optString("uid"),
            summary = invite.optString("summary"),
            location = invite.optString("location"),
            organizer = invite.optString("organizer"),
            dtstart = invite.optJSONObject("dtstart")?.optString("iso"),
            dtend = invite.optJSONObject("dtend")?.optString("iso"),
        )
    }.getOrNull()

    fun respond(emailId: String, partId: String, response: String): Boolean = runCatching {
        val form = FormBody.Builder()
            .add("emailId", emailId)
            .add("partId", partId)
            .add("response", response)
            .build()
        val request = Request.Builder()
            .url("$base/api/v2/calendar-invite/respond")
            .header("Authorization", credential)
            .header("Accept", "application/json")
            .post(form)
            .build()
        client.newCall(request).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private fun String.urlEncode(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
