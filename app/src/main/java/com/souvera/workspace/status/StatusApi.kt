/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.status

import com.souvera.workspace.dav.DavAccount
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Minimal client for the Nextcloud user_status OCS API (get/set the global online status). */
class StatusApi(private val dav: DavAccount) {

    private val base = dav.baseUrl.trimEnd('/') + "/ocs/v2.php/apps/user_status/api/v1/user_status"
    private val credential = Credentials.basic(dav.username, dav.password)
    private val client = OkHttpClient()

    fun current(): UserStatusType? {
        val request = signed(Request.Builder().url(base)).get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val status = JSONObject(response.body.string())
                .optJSONObject("ocs")?.optJSONObject("data")?.optString("status")
            UserStatusType.fromApi(status)
        }
    }

    fun set(type: UserStatusType): Boolean {
        val form = FormBody.Builder().add("statusType", type.apiValue).build()
        val request = signed(Request.Builder().url("$base/status")).put(form).build()
        return client.newCall(request).execute().use { it.isSuccessful }
    }

    private fun signed(builder: Request.Builder): Request.Builder = builder
        .header("Authorization", credential)
        .header("OCS-APIRequest", "true")
        .header("Accept", "application/json")
}
