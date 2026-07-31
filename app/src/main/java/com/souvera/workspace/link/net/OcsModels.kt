/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.net

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

/** Generic Nextcloud OCS v2 response envelope: `{ocs: {meta, data}}`. */
data class OcsEnvelope<T>(@SerializedName("ocs") val ocs: OcsBody<T>)

data class OcsBody<T>(@SerializedName("meta") val meta: OcsMeta, @SerializedName("data") val data: T?)

data class OcsMeta(
    @SerializedName("status") val status: String?,
    @SerializedName("statuscode") val statusCode: Int,
    @SerializedName("message") val message: String?
)

/** A Talk ("Link") conversation as returned by `spreed`'s room API. */
data class LinkConversation(
    @SerializedName("token") val token: String,
    @SerializedName("displayName") val displayName: String,
    @SerializedName("type") val type: Int,
    @SerializedName("unreadMessages") val unreadMessages: Int = 0,
    @SerializedName("hasCall") val hasCall: Boolean = false,
    @SerializedName("lastActivity") val lastActivity: Long = 0L,
    @SerializedName("lastCommonReadMessage") val lastCommonReadMessage: Long = 0L,
    @SerializedName("lastMessage") val lastMessage: com.google.gson.JsonElement? = null
) {
    /** Last message preview text; `lastMessage` is a chat-message object, or `[]`/absent when none. */
    fun lastMessageText(): String {
        val obj = lastMessage?.takeIf { it.isJsonObject }?.asJsonObject ?: return ""
        val text = obj.get("message")?.asString.orEmpty()
        val params = obj.get("messageParameters")?.takeIf { it.isJsonObject }?.asJsonObject
        val file = params?.entrySet()?.firstOrNull {
            it.value.isJsonObject && it.value.asJsonObject.get("type")?.asString == "file"
        }?.value?.asJsonObject?.get("name")?.asString
        return file?.let { "📎 $it" } ?: text
    }
}

/** A single chat message in a conversation. */
data class LinkChatMessage(
    @SerializedName("id") val id: Long,
    @SerializedName("token") val token: String = "",
    @SerializedName("actorId") val actorId: String = "",
    @SerializedName("actorDisplayName") val actorDisplayName: String = "",
    @SerializedName("actorType") val actorType: String = "",
    @SerializedName("timestamp") val timestamp: Long = 0L,
    @SerializedName("message") val message: String = "",
    @SerializedName("systemMessage") val systemMessage: String = "",
    @SerializedName("messageParameters") val messageParameters: JsonElement? = null
) {
    /** File name if this message is a shared file (Talk puts a `file` rich-object parameter), else null. */
    fun fileName(): String? = fileParam()?.get("name")?.asString

    /** Browser link to open the shared file in Nextcloud, else null. */
    fun fileLink(): String? = fileParam()?.get("link")?.asString

    /** Nextcloud file id of the shared file, for preview/download, else null. */
    fun fileId(): String? = fileParam()?.get("id")?.asString

    /** Server-relative path of the shared file (for WebDAV download), else null. */
    fun filePath(): String? = fileParam()?.get("path")?.asString

    fun fileMimeType(): String? = fileParam()?.get("mimetype")?.asString

    fun isImageFile(): Boolean = fileMimeType()?.startsWith("image/") == true ||
        (fileParam()?.get("preview-available")?.asString == "yes")

    private fun fileParam(): com.google.gson.JsonObject? {
        val params = messageParameters?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return params.entrySet()
            .firstOrNull { it.value.isJsonObject && it.value.asJsonObject.get("type")?.asString == "file" }
            ?.value?.asJsonObject
    }
}

/** A user/group suggestion from the autocomplete API, used to start a new conversation. */
data class LinkSuggestion(
    @SerializedName("id") val id: String,
    @SerializedName("label") val label: String,
    @SerializedName("source") val source: String = ""
)
