/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.net.jmap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * High-level JMAP API that builds method-call arguments and delegates to
 * [JmapClient]. One instance per account. Used by the repository layer.
 */
class JmapApi(private val client: JmapClient) {

    suspend fun primaryAccountId(): String = withContext(Dispatchers.IO) {
        client.refreshSession().primaryAccountId
    }

    /* ---------- Mailbox/get ------------------------------------------- */

    suspend fun getMailboxes(accountId: String): JSONArray = withContext(Dispatchers.IO) {
        val args = JSONObject().apply {
            if (accountId.isNotBlank()) put("accountId", accountId)
            put("ids", JSONObject.NULL)
        }
        val resp = client.singleCall("Mailbox/get", args)
        val list = resp.optJSONArray("list")
        if (list == null) throw JmapException("Mailbox/get returned no list")
        list
    }

    suspend fun getMailboxesByIds(accountId: String, ids: List<String>): JSONArray = withContext(Dispatchers.IO) {
        val args = JSONObject().apply {
            if (accountId.isNotBlank()) put("accountId", accountId)
            put("ids", JSONArray(ids))
        }
        val resp = client.singleCall("Mailbox/get", args)
        val list = resp.optJSONArray("list")
        if (list == null) throw JmapException("Mailbox/get returned no list")
        list
    }

    /* ---------- Email/query ------------------------------------------- */

    suspend fun queryEmails(
        accountId: String,
        inMailboxId: String,
        sort: JSONArray? = null,
        limit: Int = 50,
        anchor: Long? = null,
        filterText: String? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        val filter = JSONObject().apply {
            if (inMailboxId.isNotBlank()) put("inMailbox", inMailboxId)
            if (!filterText.isNullOrBlank()) put("text", filterText)
        }
        val args = JSONObject().apply {
            if (accountId.isNotBlank()) put("accountId", accountId)
            put("filter", filter)
            put("collapseThreads", false)
            if (sort != null) put("sort", sort)
            put("position", 0)
            if (anchor != null) put("anchor", anchor)
            put("limit", limit)
        }
        client.singleCall("Email/query", args)
    }

    suspend fun queryEmailChanges(
        accountId: String,
        sinceState: String?,
        inMailboxId: String? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        val filter = if (!inMailboxId.isNullOrBlank()) JSONObject().apply {
            put("inMailbox", inMailboxId)
        } else JSONObject.NULL
        val args = JSONObject().apply {
            if (accountId.isNotBlank()) put("accountId", accountId)
            if (filter !== JSONObject.NULL) put("filter", filter)
            if (!sinceState.isNullOrBlank()) put("sinceQueryState", sinceState)
        }
        client.singleCall("Email/queryChanges", args)
    }

    /* ---------- Email/get --------------------------------------------- */

    suspend fun getEmails(
        accountId: String,
        ids: List<String>,
        bodyProperties: JSONArray? = null
    ): JSONArray = withContext(Dispatchers.IO) {
        val args = JSONObject().apply {
            if (accountId.isNotBlank()) put("accountId", accountId)
            put("ids", JSONArray(ids))
            if (bodyProperties != null) put("bodyProperties", bodyProperties)
        }
        val resp = client.singleCall("Email/get", args)
        val list = resp.optJSONArray("list")
        if (list == null) throw JmapException("Email/get returned no list")
        list
    }

    /* ---------- Email/set --------------------------------------------- */

    suspend fun setEmailFlags(
        accountId: String,
        emailIds: List<String>,
        keywordsToAdd: Map<String, Boolean>,
        keywordsToRemove: List<String>
    ): JSONObject = withContext(Dispatchers.IO) {
        val updates = JSONObject()
        emailIds.forEach { id ->
            updates.put(id, JSONObject().apply {
                if (keywordsToAdd.isNotEmpty()) {
                    val addObj = JSONObject()
                    keywordsToAdd.forEach { (k, v) -> addObj.put(k, v) }
                    put("keywords/\$add", addObj)
                }
                if (keywordsToRemove.isNotEmpty()) {
                    put("keywords/\$remove", JSONArray(keywordsToRemove))
                }
            })
        }
        val args = JSONObject().apply {
            if (accountId.isNotBlank()) put("accountId", accountId)
            put("update", updates)
        }
        client.singleCall("Email/set", args)
    }

    suspend fun moveEmails(
        accountId: String,
        emailIds: List<String>,
        targetMailboxId: String
    ): JSONObject = withContext(Dispatchers.IO) {
        val updates = JSONObject()
        emailIds.forEach { id ->
            updates.put(id, JSONObject().apply {
                put("mailboxIds", JSONObject().apply {
                    put(targetMailboxId, true)
                })
            })
        }
        val args = JSONObject().apply {
            if (accountId.isNotBlank()) put("accountId", accountId)
            put("update", updates)
        }
        client.singleCall("Email/set", args)
    }

    suspend fun deleteEmails(
        accountId: String,
        emailIds: List<String>
    ): JSONObject = withContext(Dispatchers.IO) {
        val args = JSONObject().apply {
            if (accountId.isNotBlank()) put("accountId", accountId)
            put("destroy", JSONArray(emailIds))
        }
        client.singleCall("Email/set", args)
    }

    /* ---------- Email/import (send) ----------------------------------- */

    suspend fun createDraft(
        accountId: String,
        mailboxId: String,
        fromAddress: String,
        toAddresses: List<String>,
        ccAddresses: List<String>,
        bccAddresses: List<String>,
        subject: String,
        htmlBody: String?,
        plainText: String?,
        inReplyTo: String?,
        blobIds: List<String>
    ): JSONObject = withContext(Dispatchers.IO) {
        val email = JSONObject().apply {
            put("mailboxIds", JSONObject().apply { put(mailboxId, true) })
            put("subject", subject)
            if (!htmlBody.isNullOrBlank()) {
                put("htmlBody", JSONArray().put(JSONObject().apply {
                    put("partId", "1")
                    put("type", "text/html")
                }))
                jsonPut("bodyValues", if (!htmlBody.isNullOrBlank()) "1" to htmlValue(htmlBody) else null)
            }
            if (!plainText.isNullOrBlank()) {
                val bodies = optJSONArray("htmlBody") ?: JSONArray()
                bodies.put(JSONObject().apply {
                    put("partId", "2")
                    put("type", "text/plain")
                })
                put("textBody", bodies)
                jsonPutVal("bodyValues", "2", textValue(plainText))
            }
            put("from", JSONArray().put(JSONObject().apply {
                put("email", fromAddress)
            }))
            if (toAddresses.isNotEmpty()) put("to", toJsonAddr(toAddresses))
            if (ccAddresses.isNotEmpty()) put("cc", toJsonAddr(ccAddresses))
            if (bccAddresses.isNotEmpty()) put("bcc", toJsonAddr(bccAddresses))
            if (!inReplyTo.isNullOrBlank()) put("inReplyTo", JSONArray().put(inReplyTo))
            if (blobIds.isNotEmpty()) {
                val atts = JSONArray()
                blobIds.forEach { id ->
                    atts.put(JSONObject().apply {
                        put("blobId", id)
                        put("type", "application/octet-stream")
                    })
                }
                put("attachments", atts)
            }
            put("keywords", JSONObject().apply { put("\$draft", true) })
        }
        val args = JSONObject().apply {
            if (accountId.isNotBlank()) put("accountId", accountId)
            put("create", JSONObject().apply { put("new", email) })
        }
        client.singleCall("Email/set", args)
    }

    suspend fun submitEmail(
        accountId: String,
        emailId: String,
        fromAddress: String
    ): JSONObject {
        val args = JSONObject().apply {
            if (accountId.isNotBlank()) put("accountId", accountId)
            put("create", JSONObject().apply {
                put("sendme", JSONObject().apply {
                    put("emailId", emailId)
                    put("identityId", fromAddress)
                })
            })
            put("onSuccessDestroyEmail", JSONArray(listOf("#sendme")))
        }
        return client.singleCall("Email/submission/set", args)
    }

    /* ---------- PushSubscription/set --------------------------------- */

    suspend fun setPushSubscription(
        accountId: String,
        deviceId: String,
        pushUrl: String
    ): JSONObject = withContext(Dispatchers.IO) {
        val args = JSONObject().apply {
            if (accountId.isNotBlank()) put("accountId", accountId)
            put("create", JSONObject().apply {
                put(deviceId, JSONObject().apply {
                    put("url", pushUrl)
                    put("types", JSONArray(listOf("Email", "Mailbox")))
                })
            })
        }
        client.singleCall("PushSubscription/set", args)
    }

    /* ---------- helpers ----------------------------------------------- */

    private fun toJsonAddr(addrs: List<String>): JSONArray {
        val arr = JSONArray()
        addrs.forEach { a -> arr.put(JSONObject().apply { put("email", a) }) }
        return arr
    }

    private fun htmlValue(body: String): JSONObject = JSONObject().apply {
        put("value", body)
    }

    private fun textValue(body: String): JSONObject = JSONObject().apply {
        put("value", body)
    }

    private fun JSONObject.jsonPut(key: String, value: Pair<String, JSONObject>?) {
        if (value == null) return
        val obj = optJSONObject("bodyValues") ?: JSONObject()
        obj.put(value.first, value.second)
        put("bodyValues", obj)
    }

    private fun JSONObject.jsonPutVal(key: String, subKey: String, subVal: JSONObject) {
        val obj = optJSONObject(key) ?: JSONObject()
        obj.put(subKey, subVal)
        put(key, obj)
    }
}
