/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.ui

import com.souvera.workspace.link.net.LinkChatMessage

/** A locally tracked message whose send failed; shown grey with a retry action. */
data class FailedChatMessage(
    val localId: Long,
    val token: String,
    val text: String,
    val timestamp: Long
)

sealed interface ChatItem {
    val key: String

    data class Separator(override val key: String, val label: String) : ChatItem

    data class Failed(
        override val key: String,
        val localId: Long,
        val text: String,
        val timestamp: Long
    ) : ChatItem

    data class Message(
        override val key: String,
        val message: LinkChatMessage,
        val mine: Boolean,
        val grouped: Boolean,
        val read: Boolean = false
    ) : ChatItem
}

/**
 * Builds the flat chat item list: messages and failed sends merged chronologically by
 * (timestamp, order) — server messages first on ties — with a date separator whenever the
 * day changes between two consecutive events.
 */
fun buildChatItems(
    messages: List<LinkChatMessage>,
    me: String,
    readUpTo: Long?,
    failed: List<FailedChatMessage>,
    token: String
): List<ChatItem> {
    data class Event(val timestamp: Long, val order: Int, val message: LinkChatMessage?, val failedMsg: FailedChatMessage?)

    val events = mutableListOf<Event>()
    messages.forEach { msg ->
        events.add(Event(timestamp = msg.timestamp, order = 0, message = msg, failedMsg = null))
    }
    failed.filter { it.token == token }.forEach { f ->
        events.add(Event(timestamp = f.timestamp, order = 1, message = null, failedMsg = f))
    }
    events.sortWith(compareBy<Event> { it.timestamp }.thenBy { it.order })

    val items = mutableListOf<ChatItem>()
    var lastDate: String? = null
    var lastActor: String? = null
    events.forEach { event ->
        val date = dateLabel(event.timestamp)
        if (date != lastDate) {
            val key = event.message?.let { "sep-${it.id}" } ?: "sep-failed-${event.failedMsg!!.localId}"
            items.add(ChatItem.Separator(key = key, label = date))
            lastDate = date
        }
        val failedMsg = event.failedMsg
        if (failedMsg != null) {
            items.add(
                ChatItem.Failed(
                    key = "failed-${failedMsg.localId}",
                    localId = failedMsg.localId,
                    text = failedMsg.text,
                    timestamp = failedMsg.timestamp
                )
            )
            lastActor = null
        } else {
            val msg = event.message!!
            val isMe = msg.actorId == me
            val grouped = msg.actorId != null && msg.actorId == lastActor && lastDate == date
            items.add(
                ChatItem.Message(
                    key = "${msg.id}",
                    message = msg,
                    mine = isMe,
                    grouped = grouped,
                    read = isMe && readUpTo != null && msg.id <= readUpTo
                )
            )
            lastActor = msg.actorId
        }
    }
    return items
}

private fun dateLabel(ts: Long): String {
    if (ts <= 0) return ""
    val now = java.util.Calendar.getInstance()
    val msgTime = java.util.Calendar.getInstance().apply { timeInMillis = ts * 1000 }
    return when {
        now.get(java.util.Calendar.YEAR) == msgTime.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == msgTime.get(java.util.Calendar.DAY_OF_YEAR) -> "Heute"
        now.get(java.util.Calendar.YEAR) == msgTime.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) - 1 == msgTime.get(java.util.Calendar.DAY_OF_YEAR) -> "Gestern"
        else -> java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
            .format(java.util.Date(ts * 1000))
    }
}
