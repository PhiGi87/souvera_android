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

    /**
     * Ensures a JMAP session exists (discovers the endpoint, fetches the
     * session resource). Idempotent; on success the session is cached.
     */
    suspend fun refreshSession(): JmapSessionInfo = withContext(Dispatchers.IO) {
        val apiUrl = resolveApiUrl()
        // Stalwart's JMAP endpoint does NOT support GET — only POST (RFC 8620
        // allows the session via authenticated POST). Send a minimal echo to
        // obtain the session object and parse capabilities/accounts from it.
        // Minimal valid JMAP request: empty methodCalls, only core capability.
        // Core/echo is optional in RFC 8620 and some servers reject it.
        val requestObj = JSONObject().apply {
            put("using", JSONArray(listOf(JmapCapabilities.CORE)))
            put("methodCalls", JSONArray())
        }
        try {
            val json = httpPost(apiUrl, requestObj.toString())
            val caps = parseSession(json)
            resolvedApiUrl = apiUrl
            session = caps
            caps
        } catch (e: JmapException) {
            // If the first attempt failed, try with echo.
            android.util.Log.w("JmapClient", "Empty-call session failed; trying Core/echo", e)
            val echoObj = JSONObject().apply {
                put("using", JSONArray(listOf(JmapCapabilities.CORE)))
                put("methodCalls", JSONArray(listOf(
                    JSONArray().put("Core/echo").put(JSONObject()).put("c1")
                )))
            }
            val json = httpPost(apiUrl, echoObj.toString())
            val caps = parseSession(json)
            resolvedApiUrl = apiUrl
            session = caps
            caps
        }
    }

    private fun parseSession(json: JSONObject): JmapSessionInfo {
        val caps = mutableMapOf<String, JSONObject>()
        val capsNode = json.optJSONObject("capabilities")
        android.util.Log.d("JmapClient", "Session caps keys: ${capsNode?.keys()?.asSequence()?.toList()}")
        capsNode?.let { c -> c.keys().forEach { k -> caps[k] = c.getJSONObject(k) } }
        val primaryMap = json.optJSONObject("primaryAccounts")
        android.util.Log.d("JmapClient", "primaryAccounts: ${primaryMap?.toString(2)}")
        val accountsMap = json.optJSONObject("accounts")
        android.util.Log.d("JmapClient", "accounts keys: ${accountsMap?.keys()?.asSequence()?.toList()}")
        val mailAccIdFromCap = caps[JmapCapabilities.MAIL]?.optString("accountId", null)
            ?.takeIf { it.isNotBlank() }
        val primaryAccId = primaryMap?.optString(JmapCapabilities.MAIL, null)
            ?.takeIf { it.isNotBlank() }
            ?: mailAccIdFromCap
        val fallbackAccId = accountsMap?.keys()?.asSequence()?.firstOrNull { key ->
            val acc = accountsMap.optJSONObject(key)
            acc?.optBoolean("isPersonal", false) == true && acc?.has("name") == true
        }
        val accId = primaryAccId ?: fallbackAccId ?: ""
        if (accId.isBlank()) {
            throw JmapException(
                "Could not resolve accountId: caps=$capsNode, primary=$primaryMap, accounts=$accountsMap"
            )
        }
        val apiUrl = resolvedApiUrl ?: json.optString("apiUrl", "")
        return JmapSessionInfo(
            apiUrl = apiUrl,
            downloadUrl = json.optString("downloadUrl", apiUrl + "/download/{account}/{blobId}/{type}/{name}"),
            uploadUrl = json.optString("uploadUrl", apiUrl + "/upload/{account}"),
            accountId = accId,
            primaryAccountId = primaryAccId ?: accId,
            username = json.optString("username", dav.username),
            capabilities = caps,
            state = json.optString("sessionState", null).takeIf { it != null }
        )
    }

    /* ---------- method calls -------------------------------------------- */

    /**
     * Sends one or more JMAP method calls as a single batch request.
     * Each [JmapMethodCall] produces exactly one [JmapCallResult] (success
     * or error) in the returned list, preserving order.
     */
    suspend fun call(calls: List<JmapMethodCall>): JmapBatchResult = withContext(Dispatchers.IO) {
        val apiUrl = resolvedApiUrl ?: resolveApiUrl()
        val using = listOf(JmapCapabilities.CORE, JmapCapabilities.MAIL,
            JmapCapabilities.SUBMISSION, JmapCapabilities.BLOB)
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
                throw JmapException("Blob upload HTTP $code", readErrorBody(conn))
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
                throw JmapException("Blob download HTTP $code", readErrorBody(conn))
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
        val wellKnown = "$base/.well-known/jmap"
        try {
            val resp = httpGet(wellKnown)
            resp?.optString("apiUrl")?.takeIf { it.isNotBlank() }
                ?: resp?.optString("downloadUrl")?.takeIf { it.contains("/jmap") }
                ?.let { it.substringBefore("/download") } ?: base + "/jmap"
        } catch (_: Exception) {
            base + "/jmap"
        }
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
        val conn = (u.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", authHeader())
            setRequestProperty("Accept", "application/json")
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        conn.outputStream.write(jsonBody.toByteArray(Charsets.UTF_8))
        conn.outputStream.close()
        val code = conn.responseCode
        if (code == 401) {
            throw JmapException("JMAP auth rejected — needs Bearer token", "HTTP 401")
        }
        val body =         if (code in 200..299) {
            String(conn.inputStream.readBytes(), Charsets.UTF_8)
        } else {
            throw JmapException("JMAP HTTP $code: " + readErrorBody(conn).take(500))
        }
        try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw JmapException("JMAP response is not valid JSON: ${body.take(200)}")
        }
    }

    private fun readErrorBody(conn: HttpURLConnection): String {
        return try {
            String(conn.errorStream?.readBytes() ?: ByteArray(0), Charsets.UTF_8).take(300)
        } catch (_: Exception) { "" }
    }
}

class JmapException(
    message: String,
    val errorType: String? = null
) : RuntimeException(message)
