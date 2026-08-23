/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.shield

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.souvera.workspace.dav.DavAccount
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Ein Eintrag der Spam-Quarantäne (PMG), wie von souvera_shield geliefert. */
data class ShieldMail(
    @SerializedName("id") val id: String = "",
    @SerializedName("from") val from: String = "",
    @SerializedName("subject") val subject: String = "",
    @SerializedName("time") val time: Long = 0,
    @SerializedName("spamlevel") val spamlevel: Double = 0.0,
    @SerializedName("_pmail") val pmail: String = ""
)

/**
 * Minimal-Client für die souvera_shield-API (NoAdminRequired, Basic-Auth mit
 * App-Passwort) — gleiche Mechanik wie OcsApi der Link-Komponente.
 */
class ShieldApi(private val dav: DavAccount) {

    private val gson = Gson()
    private val base = dav.baseUrl.trimEnd('/') + "/apps/souvera_shield"
    private val credential = Credentials.basic(dav.username, dav.password)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun listQuarantine(): List<ShieldMail> {
        val body = get("$base/api/quarantine") ?: return emptyList()
        val type = object : TypeToken<ShieldEnvelope<List<ShieldMail>>>() {}.type
        return gson.fromJson<ShieldEnvelope<List<ShieldMail>>>(body, type).data.orEmpty()
    }

    /** Gibt den Text-Body der Nachricht zurück (zur Anzeige in der Vorschau). */
    fun viewMessage(id: String, email: String): String? {
        val body = get("$base/api/quarantine/view?id=$id&email=${email.urlEncode()}") ?: return null
        return body
    }

    fun release(id: String, email: String): Boolean =
        post("$base/api/quarantine/release", "id" to id, "email" to email)

    /** Kombi: freigeben UND Absender whitelisten (souvera_shield v4.0.57). */
    fun releaseWhitelist(id: String, email: String, entry: String): Boolean =
        post("$base/api/quarantine/release-whitelist", "id" to id, "email" to email, "entry" to entry)

    fun delete(id: String, email: String): Boolean =
        post("$base/api/quarantine/delete", "id" to id, "email" to email)

    /** Setzt eine Absender-Adresse auf die PMG-Blacklist aller Identitäten. */
    fun blacklist(entry: String): Boolean =
        post("$base/api/blacklist", "entry" to entry)

    private fun post(url: String, vararg params: Pair<String, String>): Boolean {
        val form = FormBody.Builder()
        params.forEach { (k, v) -> form.add(k, v) }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", credential)
            .header("OCS-APIRequest", "true")
            .header("Accept", "application/json")
            .post(form.build())
            .build()
        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(TAG, "Shield POST failed", e)
            false
        }
    }

    private fun get(url: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", credential)
            .header("OCS-APIRequest", "true")
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "Shield GET failed", e)
        null
    }

    private fun String.urlEncode(): String =
        java.net.URLEncoder.encode(this, "UTF-8")

    private data class ShieldEnvelope<T>(val data: T?)

    companion object {
        private const val TAG = "ShieldApi"
    }
}
