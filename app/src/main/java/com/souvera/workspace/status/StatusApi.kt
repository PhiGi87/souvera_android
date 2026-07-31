/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.status

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.souvera.workspace.dav.DavAccount
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Minimal client for the Nextcloud user_status OCS API (get/set the global online status). */
class StatusApi(private val dav: DavAccount) {

    private val base = dav.baseUrl.trimEnd('/') + "/ocs/v2.php/apps/user_status/api/v1"
    private val credential = Credentials.basic(dav.username, dav.password)
    private val client = OkHttpClient()

    fun current(): UserStatusType? {
        val request = signed(Request.Builder().url("$base/user_status")).get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val status = JSONObject(response.body.string())
                .optJSONObject("ocs")?.optJSONObject("data")?.optString("status")
            UserStatusType.fromApi(status)
        }
    }

    fun set(type: UserStatusType): Boolean {
        val form = FormBody.Builder().add("statusType", type.apiValue).build()
        val request = signed(Request.Builder().url("$base/user_status/status")).put(form).build()
        return client.newCall(request).execute().use { it.isSuccessful }
    }

    /** Presence of a single other user (status + last activity), or null if not available. */
    fun peerStatus(userId: String): PeerStatus? {
        val encoded = java.net.URLEncoder.encode(userId, "UTF-8").replace("+", "%20")
        val url = "$base/statuses/$encoded"
        val request = signed(Request.Builder().url(url)).get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            parseStatusBody(response.body.string())
        }
    }

    private fun signed(builder: Request.Builder): Request.Builder = builder
        .header("Authorization", credential)
        .header("OCS-APIRequest", "true")
        .header("Accept", "application/json")

    companion object {
        private val gson = Gson()

        /** Parses a `GET /statuses/{userId}` response body into presence, or null if absent. */
        internal fun parseStatusBody(body: String): PeerStatus? {
            val type = object : TypeToken<StatusEnvelope>() {}.type
            val data = runCatching { gson.fromJson<StatusEnvelope>(body, type)?.ocs?.data }.getOrNull()
                ?: return null
            return PeerStatus(
                status = UserStatusType.fromApi(data.status),
                lastActivity = data.lastActivity
            )
        }

        private data class StatusEnvelope(@SerializedName("ocs") val ocs: OcsBody?)
        private data class OcsBody(@SerializedName("data") val data: StatusData?)
        private data class StatusData(
            @SerializedName("status") val status: String?,
            @SerializedName("lastActivity") val lastActivity: Long = 0L
        )
    }
}
