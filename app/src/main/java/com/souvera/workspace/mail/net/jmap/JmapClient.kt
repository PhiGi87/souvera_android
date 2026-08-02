/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.net.jmap

import com.souvera.workspace.dav.DavAccount
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Low-level JMAP client. One instance per account.
 *
 * Handles session discovery (`.well-known/jmap` → `/jmap`), batch method-call
 * encoding, authentication (Basic, with Bearer fallback via [needsBearerToken]),
 * and blob upload/download. All HTTP calls run on [Dispatchers.IO].
 *
 * Errors are surfaced as [JmapCallResult.Failure] with a [JmapError]; the caller
 * does not need special JSON-path logic.
 */
class JmapClient(
    private val dav: DavAccount,
    private var bearerToken: String? = null
) {
    private var session: JmapSessionInfo? = null
    private var resolvedApiUrl: String? = null

    /* ---------- session ------------------------------------------------- */

    private var resolvedSession: JSONObject? = null

    /**
     * Ensures a JMAP session exists (discovers the endpoint, fetches the
     * session resource). Idempotent; on success the session is cached.
     */
    suspend fun refreshSession(): JmapSessionInfo = withContext(Dispatchers.IO) {
        val apiUrl = resolveApiUrl()
        val sessionJson = resolvedSession
            ?: throw JmapException("Cannot fetch JMAP session from $apiUrl")
        val caps = parseSession(sessionJson)
        resolvedApiUrl = apiUrl
        session = caps
        caps
    }

    private fun parseSession(json: JSONObject): JmapSessionInfo {
        val caps = mutableMapOf<String, JSONObject>()
        json.optJSONObject("capabilities")?.let { c ->
            c.keys().forEach { k -> caps[k] = c.getJSONObject(k) }
        }
        val primaryAccId = json.optJSONObject("primaryAccounts")
            ?.optString(JmapCapabilities.MAIL, null)
            ?.takeIf { it.isNotBlank() }
            ?: caps[JmapCapabilities.MAIL]?.optString("accountId", null)
                ?.takeIf { it.isNotBlank() }
        val accId = dav.username
        val apiUrl = json.optString("apiUrl", "").takeIf { it.isNotBlank() }
            ?: (resolvedApiUrl ?: "")
        return JmapSessionInfo(
            apiUrl = apiUrl,
            downloadUrl = json.optString("downloadUrl", apiUrl + "download/{accountId}/{blobId}/{name}?accept={type}"),
            uploadUrl = json.optString("uploadUrl", apiUrl + "upload/{accountId}/"),
            accountId = "",   // empty — Stalwart resolves from auth
            primaryAccountId = accId,
            username = json.optString("username", dav.username).takeIf { it.isNotBlank() } ?: dav.username,
            capabilities = caps,
            state = json.optString("state", null).takeIf { !it.isNullOrBlank() }
        )
    }

    /** Like [httpGet] but returns null on non-2xx instead of throwing. */

    /* ---------- method calls -------------------------------------------- */

    /**
     * Sends one or more JMAP method calls as a single batch request.
     * Each [JmapMethodCall] produces exactly one [JmapCallResult] (success
     * or error) in the returned list, preserving order.
     */
    suspend fun call(calls: List<JmapMethodCall>, using: List<String> = listOf(JmapCapabilities.CORE, JmapCapabilities.MAIL)): JmapBatchResult = withContext(Dispatchers.IO) {
        val apiUrl = resolvedApiUrl ?: resolveApiUrl()
        val using = using
        val requestObj = JSONObject().apply {
            put("using", JSONArray(using))
            put("methodCalls", JSONArray().apply {
                calls.forEach { put(JSONArray().put(it.name).put(it.args).put(it.callId)) }
            })
        }
        val response = httpPost(apiUrl, requestObj.toString())
        val responses = response.optJSONArray("methodResponses")
            ?: throw JmapException("No methodResponses in JMAP response")
        val sessionState = response.optString("sessionState", null).takeIf { it != null }
        val results = mutableListOf<JmapCallResult>()
        for (i in 0 until responses.length()) {
            val triple = responses.getJSONArray(i)
            val name = triple.optString(0, "")
            val args = triple.optJSONObject(1) ?: JSONObject()
            val callId = triple.optString(2, "")
            if (name == "error" || args.has("type")) {
                results.add(JmapCallResult.Failure(JmapError.from(args, callId)))
            } else {
                results.add(JmapCallResult.Success(JmapMethodResponse(name, args, callId)))
            }
        }
        JmapBatchResult(results, sessionState)
    }

    /**
     * Convenience: single call with auto-generated callId. Returns the
     * response args on success, or throws [JmapException] on error.
     */
    suspend fun singleCall(name: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val result = call(listOf(JmapMethodCall(name, args, "S")))
        val r = result.results.singleOrNull() ?: throw JmapException("Empty batch result")
        when (r) {
            is JmapCallResult.Success -> r.response.args
            is JmapCallResult.Failure ->
                throw JmapException("JMAP ${r.error.type}: ${r.error.description ?: "no description"}",
                    r.error.type)
        }
    }

    /* ---------- blobs --------------------------------------------------- */

    suspend fun uploadBlob(accountId: String, bytes: ByteArray, contentType: String): JmapBlobUploadResponse =
        withContext(Dispatchers.IO) {
            val url = (session ?: refreshSession()).uploadUrl
                .replace("{accountId}", accountId)
                .replace("{account}", accountId)
            val u = URL(url)
            val conn = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", contentType)
                setRequestProperty("Authorization", authHeader())
                doOutput = true
                connectTimeout = 30_000
                readTimeout = 60_000
            }
            conn.outputStream.write(bytes)
            conn.outputStream.close()
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = try { String(conn.errorStream?.readBytes() ?: ByteArray(0), Charsets.UTF_8).take(300) } catch (_: Exception) { "" }
                throw JmapException("Blob upload HTTP $code: $err")
            }
            val json = JSONObject(String(conn.inputStream.readBytes(), Charsets.UTF_8))
            JmapBlobUploadResponse(
                blobId = json.getString("blobId"),
                size = json.getLong("size"),
                type = json.getString("type")
            )
        }

    suspend fun downloadBlob(accountId: String, blobId: String, mimeType: String): ByteArray =
        withContext(Dispatchers.IO) {
            val url = (session ?: refreshSession()).downloadUrl
                .replace("{accountId}", accountId)
                .replace("{account}", accountId)
                .replace("{blobId}", blobId)
                .replace("{type}", mimeType)
                .replace("{name}", blobId)
                .replace("{type}", mimeType)
            val u = URL(url)
            val conn = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", mimeType)
                setRequestProperty("Authorization", authHeader())
                connectTimeout = 15_000
                readTimeout = 60_000
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = try { String(conn.errorStream?.readBytes() ?: ByteArray(0), Charsets.UTF_8).take(300) } catch (_: Exception) { "" }
                throw JmapException("Blob download HTTP $code: $err")
            }
            conn.inputStream.readBytes()
        }

    /* ---------- auth ---------------------------------------------------- */

    private fun authHeader(): String {
        val bearer = bearerToken
        if (bearer != null) return "Bearer $bearer"
        val cred = android.util.Base64.encodeToString(
            "${dav.username}:${dav.password}".toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        return "Basic $cred"
    }

    /** Call when a 401 is received with Basic auth to retry with Bearer. */
    fun setBearerToken(token: String) {
        bearerToken = token
    }

    fun needsBearerToken(): Boolean = bearerToken == null

    /* ---------- internal ----------------------------------------------- */

    private suspend fun resolveApiUrl(): String = withContext(Dispatchers.IO) {
        val base = dav.baseUrl.trimEnd('/')
        // Try the session endpoint directly (GET /jmap/session, no auth needed).
        // This is what .well-known/jmap redirects to on Stalwart, and the
        // redirect-follow via HttpURLConnection is unreliable on some devices.
        val sessionUrl = "$base/jmap/session"
        try {
            val resp = httpGet(sessionUrl)
            if (resp != null) {
                resolvedSession = resp
                return@withContext resp.optString("apiUrl").takeIf { it.isNotBlank() }
                    ?: base + "/jmap"
            }
        } catch (_: Exception) { }
        // Fallback: well-known discovery (may fail due to redirect handling).
        try {
            val resp = httpGet("$base/.well-known/jmap")
            if (resp != null) {
                resolvedSession = resp
                return@withContext resp.optString("apiUrl").takeIf { it.isNotBlank() }
                    ?: base + "/jmap"
            }
        } catch (_: Exception) { }
        base + "/jmap"
    }

    private suspend fun httpGet(urlStr: String): JSONObject? = withContext(Dispatchers.IO) {
        val u = URL(urlStr)
        val conn = (u.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", authHeader())
            setRequestProperty("Accept", "application/json")
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        val code = conn.responseCode
        if (code == 401) {
            throw JmapException("JMAP auth rejected — needs Bearer token", "HTTP 401")
        }
        if (code !in 200..299) return@withContext null
        val body = String(conn.inputStream.readBytes(), Charsets.UTF_8)
        try {
            JSONObject(body)
        } catch (_: JSONException) {
            null
        }
    }

    private suspend fun httpPost(urlStr: String, jsonBody: String): JSONObject = withContext(Dispatchers.IO) {
        val u = URL(urlStr)
        val bodyBytes = jsonBody.toByteArray(Charsets.UTF_8)
        val conn = (u.openConnection() as HttpURLConnection).apply {
            doOutput = true
            setFixedLengthStreamingMode(bodyBytes.size)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", authHeader())
            setRequestProperty("Accept", "application/json")
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        conn.outputStream.use { it.write(bodyBytes) }
        val code = conn.responseCode
        val responseBody = try {
            if (code in 200..299) String(conn.inputStream.readBytes(), Charsets.UTF_8)
            else String(conn.errorStream?.readBytes() ?: ByteArray(0), Charsets.UTF_8).take(500)
        } catch (_: Exception) { "" }
        if (code == 401) {
            throw JmapException("JMAP auth rejected — needs Bearer token: $responseBody")
        }
        if (code !in 200..299) {
            throw JmapException("JMAP HTTP $code: $responseBody")
        }
        try {
            JSONObject(responseBody)
        } catch (e: JSONException) {
            throw JmapException("JMAP response not JSON (${responseBody.take(200)})")
        }
    }

}

class JmapException(
    message: String,
    val errorType: String? = null
) : RuntimeException(message)
