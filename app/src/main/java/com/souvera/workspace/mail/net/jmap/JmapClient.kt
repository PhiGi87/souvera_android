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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
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

    // OkHttp statt HttpURLConnection: identischer Transport-Stack wie der
    // (nachweislich funktionierende) Login-Flow. Der HttpURLConnection-Stack
    // wird vom Workspace-Gateway bei /jmap mit notRequest abgelehnt.
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /* ---------- session ------------------------------------------------- */

    private var resolvedSession: JSONObject? = null

    /** Returns the raw session JSON (includes accounts, primaryAccounts). Null until refreshSession() runs. */
    fun getSessionJson(): JSONObject? = resolvedSession

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
        val accountsJson = json.optJSONObject("accounts")
        val primaryAccId = json.optJSONObject("primaryAccounts")
            ?.optString(JmapCapabilities.MAIL, null)
            ?.takeIf { it.isNotBlank() }
            ?: caps[JmapCapabilities.MAIL]?.optString("accountId", null)
                ?.takeIf { it.isNotBlank() }
        // Nur gueltige IDs akzeptieren: der Wert muss ein Schluessel der
        // `accounts`-Map sein (RFC 8620 §2). Sonst faellt z. B. eine
        // Proxy-/Stub-Session mit Fremdwerten unbemerkt durch.
        val validated = primaryAccId
            ?.takeIf { accountsJson?.has(it) ?: true }
        val accId = validated
            ?: accountsJson?.keys()?.asSequence()
                ?.mapNotNull { k ->
                    k.takeIf { accountsJson.optJSONObject(k)?.optBoolean("isPersonal", true) == true }
                }
                ?.firstOrNull()
            ?: throw JmapException(
                "JMAP session has no usable accountId (primaryAccounts=" +
                json.optJSONObject("primaryAccounts") +
                ", account keys=" + (accountsJson?.keys()?.asSequence()?.joinToString() ?: "null") +
                "). Verify that souvera_mail is correctly configured on the server."
            )
        val apiUrl = json.optString("apiUrl", "").takeIf { it.isNotBlank() }
            ?: (resolvedApiUrl ?: "")
        return JmapSessionInfo(
            apiUrl = apiUrl,
            downloadUrl = json.optString("downloadUrl", apiUrl + "download/{accountId}/{blobId}/{name}?accept={type}"),
            uploadUrl = json.optString("uploadUrl", apiUrl + "upload/{accountId}/"),
            accountId = accId,
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
        // org.json escaped "/" als "\/" (z. B. "Mailbox\/get"). Das ist zwar
        // gueltiges JSON, wird aber vom Workspace-Stalwart mit notRequest
        // abgelehnt (live reproduziert). Der Ersatz ist semantisch ein No-op.
        val requestStr = requestObj.toString().replace("\\/", "/")
        val response = httpPost(apiUrl, requestStr)
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
    suspend fun singleCall(name: String, args: JSONObject, using: List<String>? = null): JSONObject = withContext(Dispatchers.IO) {
        val result = call(listOf(JmapMethodCall(name, args, "S")), using ?: listOf(JmapCapabilities.CORE, JmapCapabilities.MAIL))
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
            val req = Request.Builder().url(url)
                .header("Authorization", authHeader())
                .header("Content-Type", contentType)
                .post(bytes.toRequestBody(contentType.toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val code = resp.code
                if (code !in 200..299) {
                    val err = resp.body?.string()?.take(300) ?: ""
                    throw JmapException("Blob upload HTTP $code: $err")
                }
                val json = JSONObject(resp.body?.string() ?: "")
                return@withContext JmapBlobUploadResponse(
                    blobId = json.getString("blobId"),
                    size = json.getLong("size"),
                    type = json.getString("type")
                )
            }
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
            val req = Request.Builder().url(url)
                .header("Authorization", authHeader())
                .header("Accept", mimeType)
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val code = resp.code
                if (code !in 200..299) {
                    val err = resp.body?.string()?.take(300) ?: ""
                    throw JmapException("Blob download HTTP $code: $err")
                }
                return@withContext resp.body?.bytes() ?: ByteArray(0)
            }
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
        val req = Request.Builder().url(urlStr)
            .header("Authorization", authHeader())
            .header("Accept", "application/json")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val code = resp.code
            if (code == 401) {
                throw JmapException("JMAP auth rejected — needs Bearer token", "HTTP 401")
            }
            if (code !in 200..299) return@withContext null
            val body = resp.body?.string() ?: return@withContext null
            try {
                JSONObject(body)
            } catch (_: JSONException) {
                null
            }
        }
    }

    private suspend fun httpPost(urlStr: String, jsonBody: String): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(urlStr)
            .header("Authorization", authHeader())
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "application/json")
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val code = resp.code
            val responseBody = resp.body?.string() ?: ""
            if (code == 401) {
                throw JmapException("JMAP auth rejected — needs Bearer token: $responseBody")
            }
            if (code !in 200..299) {
                // Kompakte, aber vollstaendige Draht-Diagnose: nur die
                // entscheidenden Felder, damit die Meldung in ein Chat-
                // Posting passt (fruehere lange Variante wurde gekuerzt).
                val ct = resp.header("Content-Type") ?: "?"
                val bodyB64 = android.util.Base64.encodeToString(
                    responseBody.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                val reqB64 = android.util.Base64.encodeToString(
                    jsonBody.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                throw JmapException(
                    "JMAP HTTP $code ct=$ct len=${responseBody.length} " +
                    "reqB64=$reqB64 respB64=$bodyB64"
                )
            }
            try {
                JSONObject(responseBody)
            } catch (e: JSONException) {
                throw JmapException("JMAP response not JSON (${responseBody.take(200)})")
            }
        }
    }

}

class JmapException(
    message: String,
    val errorType: String? = null
) : RuntimeException(message)
