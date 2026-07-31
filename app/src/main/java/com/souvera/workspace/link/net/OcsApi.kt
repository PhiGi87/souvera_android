/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.net

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.souvera.workspace.dav.DavAccount
import java.util.concurrent.TimeUnit
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Thin client for the Nextcloud Talk ("Link") OCS API (app `spreed`). Uses HTTP Basic with the
 * account app-password, exactly like the DAV layer. Long-poll requests get their own long read
 * timeout; everything else uses the short one. Chat is API v1, rooms are API v4.
 */
class OcsApi(private val dav: DavAccount) {

    private val gson = Gson()
    private val base = dav.baseUrl.trimEnd('/') + "/ocs/v2.php/apps/spreed"
    private val credential = Credentials.basic(dav.username, dav.password)

    // A shared cookie jar is required: "join room" (participants/active) establishes a server-side
    // session whose cookie must be carried into the subsequent "join call" request, otherwise the
    // server sees no active participant session and returns 404.
    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .cookieJar(SessionCookieJar())
        .build()

    fun listConversations(): List<LinkConversation> {
        val body = get("$base/api/v4/room") ?: return emptyList()
        val type = object : TypeToken<OcsEnvelope<List<LinkConversation>>>() {}.type
        return gson.fromJson<OcsEnvelope<List<LinkConversation>>>(body, type).ocs.data.orEmpty()
    }

    /**
     * Recent history (lookIntoFuture=0) or the long-poll for new messages (lookIntoFuture=1). For the
     * initial history fetch `lastKnownId` is 0 and MUST be omitted — sending `lastKnownMessageId=0`
     * with lookIntoFuture=0 means "messages older than 0" and returns nothing. The long-poll always
     * sends it (0 = from the beginning).
     */
    fun getMessages(token: String, lastKnownId: Long, future: Boolean, timeoutSeconds: Int): List<LinkChatMessage> {
        val includeLastKnown = future || lastKnownId > 0
        val lastKnownParam = if (includeLastKnown) "&lastKnownMessageId=$lastKnownId" else ""
        val url = "$base/api/v1/chat/$token?lookIntoFuture=${if (future) 1 else 0}" +
            "$lastKnownParam&timeout=$timeoutSeconds&limit=$PAGE_LIMIT&setReadMarker=1"
        val body = get(url) ?: return emptyList()
        val type = object : TypeToken<OcsEnvelope<List<LinkChatMessage>>>() {}.type
        return gson.fromJson<OcsEnvelope<List<LinkChatMessage>>>(body, type).ocs.data.orEmpty()
    }

    /** Sends a chat message; returns true when the server accepted it. */
    fun sendMessage(token: String, message: String, referenceId: String? = null): Boolean {
        val payload = gson.toJson(
            mapOf("message" to message).plus(referenceId?.let { mapOf("referenceId" to it) }.orEmpty())
        )
        val request = signed(Request.Builder().url("$base/api/v1/chat/$token"))
            .post(payload.toRequestBody(JSON))
            .build()
        return client.newCall(request).execute().use { it.isSuccessful }
    }

    /** Autocomplete users/groups to start a new conversation with. */
    fun searchUsers(query: String): List<LinkSuggestion> {
        val root = dav.baseUrl.trimEnd('/')
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$root/ocs/v2.php/core/autocomplete/get?search=$encoded&itemType=call&itemId=new" +
            "&shareTypes[]=0&shareTypes[]=1&limit=$SEARCH_LIMIT"
        val body = get(url) ?: return emptyList()
        val type = object : TypeToken<OcsEnvelope<List<LinkSuggestion>>>() {}.type
        return gson.fromJson<OcsEnvelope<List<LinkSuggestion>>>(body, type).ocs.data.orEmpty()
    }

    /** Creates (or returns) a conversation with [invite]; roomType 1 = one-to-one user, 2 = group. */
    fun createConversation(invite: String, roomType: Int): String? {
        val encoded = java.net.URLEncoder.encode(invite, "UTF-8")
        val src = if (roomType == ROOM_TYPE_GROUP) "&source=groups" else ""
        val request = signed(Request.Builder().url("$base/api/v4/room?roomType=$roomType&invite=$encoded$src"))
            .post(ByteArray(0).toRequestBody(null))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val type = object : TypeToken<OcsEnvelope<LinkConversation>>() {}.type
            return gson.fromJson<OcsEnvelope<LinkConversation>>(response.body.string(), type).ocs.data?.token
        }
    }

    /** Uploads [bytes] to the user's Talk/ folder over WebDAV and shares it into the conversation. */
    fun shareFile(token: String, fileName: String, mimeType: String, bytes: ByteArray) {
        val root = dav.baseUrl.trimEnd('/')
        val filesRoot = "$root/remote.php/dav/files/${java.net.URLEncoder.encode(dav.username, "UTF-8")}"
        client.newCall(signed(Request.Builder().url("$filesRoot/Talk/")).method("MKCOL", null).build())
            .execute().use { /* ignore: folder may already exist */ }
        val put = signed(Request.Builder().url("$filesRoot/Talk/${java.net.URLEncoder.encode(fileName, "UTF-8")}"))
            .put(bytes.toRequestBody(mimeType.toMediaType()))
            .build()
        client.newCall(put).execute().use { if (!it.isSuccessful) return }
        val shareUrl = "$root/ocs/v2.php/apps/files_sharing/api/v1/shares" +
            "?path=/Talk/${java.net.URLEncoder.encode(fileName, "UTF-8")}&shareType=$SHARE_TYPE_ROOM&shareWith=$token"
        client.newCall(signed(Request.Builder().url(shareUrl)).post(ByteArray(0).toRequestBody(null)).build())
            .execute().use { /* share creates the chat message; poll surfaces it */ }
    }

    /**
     * The single other user participant of a one-to-one room (for online status), or null in
     * group rooms / when not determinable.
     */
    fun getPeerUserId(token: String, selfId: String): String? {
        val url = "$base/api/v4/room/$token/participants"
        val body = get(url) ?: return null
        val type = object : TypeToken<OcsEnvelope<List<LinkParticipant>>>() {}.type
        val peers = gson.fromJson<OcsEnvelope<List<LinkParticipant>>>(body, type).ocs.data.orEmpty()
            .filter { it.actorType == "users" && it.actorId != selfId }
        return peers.singleOrNull()?.actorId
    }

    fun getSignalingSettings(token: String): com.souvera.workspace.link.call.SignalingSettings? {
        val root = dav.baseUrl.trimEnd('/')
        val url = "$root/ocs/v2.php/apps/spreed/api/v3/signaling/settings?token=$token"
        val body = get(url) ?: return null
        val type = object : TypeToken<OcsEnvelope<com.souvera.workspace.link.call.SignalingSettings>>() {}.type
        return gson.fromJson<OcsEnvelope<com.souvera.workspace.link.call.SignalingSettings>>(body, type).ocs.data
    }

    /** Joins the room as an active participant; returns the Nextcloud session id (for HPB room join). */
    fun joinRoom(token: String): String? {
        val request = signed(Request.Builder().url("$base/api/v4/room/$token/participants/active"))
            .post(ByteArray(0).toRequestBody(null))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val type = object : TypeToken<OcsEnvelope<JoinRoomData>>() {}.type
            return gson.fromJson<OcsEnvelope<JoinRoomData>>(response.body.string(), type).ocs.data?.sessionId
        }
    }

    /** Fetches a Nextcloud preview image (JPEG/PNG bytes) for a shared file, or null if unavailable. */
    fun previewBytes(fileId: String, size: Int): ByteArray? {
        val root = dav.baseUrl.trimEnd('/')
        val url = "$root/index.php/core/preview?fileId=$fileId&x=$size&y=$size&a=1&forceIcon=0"
        val request = Request.Builder().url(url).header("Authorization", credential).get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body.bytes()
        }
    }

    /** Downloads a shared file's raw bytes over WebDAV (by its server path), or null on failure. */
    fun downloadFile(path: String): ByteArray? {
        val root = dav.baseUrl.trimEnd('/')
        val user = java.net.URLEncoder.encode(dav.username, "UTF-8")
        val encodedPath = path.trimStart('/').split('/').joinToString("/") {
            java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        val url = "$root/remote.php/dav/files/$user/$encodedPath"
        val request = signed(Request.Builder().url(url)).get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body.bytes()
        }
    }

    fun joinCall(token: String, flags: Int) {
        // recordingConsent is mandatory when the server enforces recording consent for calls;
        // omitting it makes joinCall fail with 400 {"error":"consent"}.
        val form = FormBody.Builder()
            .add("flags", flags.toString())
            .add("silent", "false")
            .add("recordingConsent", "true")
            .build()
        val request = signed(Request.Builder().url("$base/api/v4/call/$token")).post(form).build()
        client.newCall(request).execute().use { response ->
            com.souvera.workspace.link.call.CallDebugLog.log(
                "OcsApi",
                "joinCall http=${response.code} body=${response.body.string().take(JOIN_LOG_LIMIT)}"
            )
        }
    }

    fun leaveCall(token: String) {
        val request = signed(Request.Builder().url("$base/api/v4/call/$token")).delete().build()
        client.newCall(request).execute().use { }
    }

    private data class JoinRoomData(@SerializedName("sessionId") val sessionId: String?)

    private fun get(url: String): String? {
        val request = signed(Request.Builder().url(url)).get().build()
        client.newCall(request).execute().use { response ->
            if (response.code == NO_CONTENT) return null
            if (!response.isSuccessful) return null
            return response.body.string()
        }
    }

    private fun signed(builder: Request.Builder): Request.Builder = builder
        .header("Authorization", credential)
        .header("OCS-APIRequest", "true")
        .header("Accept", "application/json")

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val CONNECT_TIMEOUT_S = 15L
        private const val READ_TIMEOUT_S = 40L
        private const val PAGE_LIMIT = 100
        private const val NO_CONTENT = 304
        private const val SEARCH_LIMIT = 20
        private const val SHARE_TYPE_ROOM = 10
        private const val ROOM_TYPE_GROUP = 2
        private const val JOIN_LOG_LIMIT = 300
    }
}
