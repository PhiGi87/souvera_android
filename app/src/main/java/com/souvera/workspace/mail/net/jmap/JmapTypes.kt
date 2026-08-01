/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.net.jmap

import org.json.JSONObject

/** JMAP RFC 8620/8621 capability constants used in `using` arrays. */
object JmapCapabilities {
    const val CORE = "urn:ietf:params:jmap:core"
    const val MAIL = "urn:ietf:params:jmap:mail"
    const val SUBMISSION = "urn:ietf:params:jmap:submission"
    const val BLOB = "urn:ietf:params:jmap:blob"
}

/** A single JMAP method call within a batch request. */
data class JmapMethodCall(
    val name: String,
    val args: JSONObject,
    val callId: String
)

/** A single JMAP method response within a batch response (from `methodResponses`). */
data class JmapMethodResponse(
    val name: String,
    val args: JSONObject,
    val callId: String
)

/** Parsed JMAP error (when name === "error" in a methodResponse). */
data class JmapError(
    val type: String,
    val description: String?,
    val callId: String?
) {
    companion object {
        fun from(args: JSONObject, callId: String?): JmapError = JmapError(
            type = args.optString("type", "unknown"),
            description = args.optString("description", null),
            callId = callId
        )
    }
}

/** Result of a single call: either a successful [response] or an [error]. */
sealed class JmapCallResult {
    data class Success(val response: JmapMethodResponse) : JmapCallResult()
    data class Failure(val error: JmapError) : JmapCallResult()
}

/** Result of `JmapClient.call()` — a batch invocation response. */
data class JmapBatchResult(
    val results: List<JmapCallResult>,
    val sessionState: String?
)

/** Cached JMAP session (from `JmapClient.refreshSession()`). */
data class JmapSessionInfo(
    val apiUrl: String,
    val downloadUrl: String,
    val uploadUrl: String,
    val accountId: String,
    val primaryAccountId: String,
    val username: String,
    val capabilities: Map<String, JSONObject>,
    val state: String?
)

/** Response to a `Blob/upload` call. */
data class JmapBlobUploadResponse(
    val blobId: String,
    val size: Long,
    val type: String
)

/** JMAP Email address object. */
data class JmapEmailAddress(
    val email: String?,
    val name: String?
)

/** JMAP Email body part (textBody / htmlBody entries). */
data class JmapBodyPart(
    val partId: String?,
    val blobId: String?,
    val size: Long,
    val type: String,
    val charset: String?,
    val name: String?
)

/** JMAP Email attachment entry. */
data class JmapAttachment(
    val blobId: String?,
    val size: Long?,
    val type: String?,
    val name: String?,
    val charset: String?,
    val partId: String?
)
