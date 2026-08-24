/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.call

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Minimal client for the Nextcloud "High Performance Backend" signaling server
 * (nextcloud-spreed-signaling protocol) used by Talk/"Link" calls. Handles the hello/welcome
 * handshake (ticket auth), joining the call's room, and relaying WebRTC offer/answer/candidate
 * messages between participant sessions. Correctness of the wire protocol is validated on-device
 * against the live HPB — see [[souvera-link-talk]].
 */
class HpbSignalingClient(
    private val settings: SignalingSettings,
    private val backendUrl: String,
    private val roomToken: String,
    private val ncSessionId: String,
    private val listener: Listener
) {

    interface Listener {
        fun onConnected(ownSessionId: String, mcuActive: Boolean)
        fun onRoomJoined()
        fun onSelfInCall()
        fun onCallParticipants(inCallRemoteSessions: List<String>)
        fun onOffer(fromSession: String, sdp: String)
        fun onAnswer(fromSession: String, sdp: String)
        fun onCandidate(fromSession: String, candidate: JsonObject)
        fun onClosed()
    }

    private val gson = Gson()
    // Ping hält die Verbindung lebendig und erkennt "half-open" Sockets nach
    // einem IP-Wechsel sofort, statt erst über TCP-Timeout.
    private val http = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null
    private var ownSessionId: String = ""

    @Volatile
    private var closed = false
    private var reconnectAttempts = 0

    fun connect() {
        val url = settings.server.trimEnd('/') + "/spreed"
        CallDebugLog.log(TAG, "connect $url (attempt ${reconnectAttempts + 1})")
        val request = Request.Builder().url(url).build()
        socket = http.newWebSocket(request, SocketListener())
    }

    private fun scheduleReconnect() {
        if (closed || reconnectAttempts >= MAX_RECONNECTS) {
            listener.onClosed()
            return
        }
        reconnectAttempts++
        // Exponentielles Backoff mit Jitter: realen WLAN<->Mobil-Handover
        // (DHCP/DNS) zeitlich abfedern, ohne dass alle Clients synchron
        // wiederverbinden. Budget ~46s statt starrer 7,5s.
        val attempt = reconnectAttempts - 1
        val base = RECONNECT_DELAY_MS shl minOf(attempt, 5)
        val jitter = kotlin.random.Random.nextLong(0, 500)
        val delay = base + jitter
        CallDebugLog.log(TAG, "reconnecting in ${delay}ms (attempt $reconnectAttempts)")
        Thread {
            runCatching { Thread.sleep(delay) }
            if (!closed) connect()
        }.start()
    }

    fun sendOffer(toSession: String, sdp: String) = sendPayload(toSession, "offer", sdp)

    fun sendAnswer(toSession: String, sdp: String) = sendPayload(toSession, "answer", sdp)

    fun sendRequestOffer(toSession: String) {
        val data = JsonObject().apply {
            addProperty("to", toSession)
            addProperty("type", "requestoffer")
            addProperty("roomType", ROOM_TYPE_VIDEO)
        }
        sendMessage(toSession, data)
    }

    fun sendCandidate(toSession: String, candidate: JsonObject) {
        val data = JsonObject().apply {
            addProperty("to", toSession)
            addProperty("type", "candidate")
            addProperty("roomType", ROOM_TYPE_VIDEO)
            add(
                "payload",
                JsonObject().apply {
                    addProperty("type", "candidate")
                    add("candidate", candidate)
                }
            )
        }
        sendMessage(toSession, data)
    }

    fun close() {
        closed = true
        runCatching { socket?.close(NORMAL_CLOSURE, null) }
        socket = null
    }

    private fun sendPayload(toSession: String, type: String, sdp: String) {
        val data = JsonObject().apply {
            addProperty("to", toSession)
            addProperty("type", type)
            addProperty("roomType", ROOM_TYPE_VIDEO)
            add(
                "payload",
                JsonObject().apply {
                    addProperty("type", type)
                    addProperty("sdp", sdp)
                }
            )
        }
        sendMessage(toSession, data)
    }

    private fun sendMessage(toSession: String, data: JsonObject) {
        val envelope = JsonObject().apply {
            addProperty("type", "message")
            add(
                "message",
                JsonObject().apply {
                    add(
                        "recipient",
                        JsonObject().apply {
                            addProperty("type", "session")
                            addProperty("sessionid", toSession)
                        }
                    )
                    add("data", data)
                }
            )
        }
        CallDebugLog.log(TAG, "send to=$toSession type=${data.get("type")?.asString}")
        socket?.send(gson.toJson(envelope))
    }

    private fun sendHello() {
        val params = JsonObject().apply {
            addProperty("userid", settings.userId)
            addProperty("ticket", settings.ticket)
        }
        val hello = JsonObject().apply {
            addProperty("version", "1.0")
            add("features", com.google.gson.JsonArray().apply { add("chat-relay") })
            add(
                "auth",
                JsonObject().apply {
                    addProperty("url", backendUrl.trimEnd('/') + "/ocs/v2.php/apps/spreed/api/v3/signaling/backend")
                    add("params", params)
                }
            )
        }
        socket?.send(
            gson.toJson(
                JsonObject().apply {
                    addProperty("type", "hello")
                    add("hello", hello)
                }
            )
        )
    }

    private fun sendRoomJoin() {
        val room = JsonObject().apply {
            addProperty("roomid", roomToken)
            addProperty("sessionid", ncSessionId)
        }
        socket?.send(
            gson.toJson(
                JsonObject().apply {
                    addProperty("type", "room")
                    add("room", room)
                }
            )
        )
    }

    private fun handle(text: String) {
        val root = runCatching { gson.fromJson(text, JsonObject::class.java) }.getOrNull() ?: return
        CallDebugLog.log(TAG, "recv ${root.get("type")?.asString}")
        when (root.get("type")?.asString) {
            "welcome" -> sendHello()
            "hello" -> handleHello(root)
            "room" -> sendReady()
            "event" -> handleEvent(root)
            "message" -> handleMessage(root)
            "error" -> CallDebugLog.log(TAG, "error $text")
            else -> Unit
        }
    }

    private fun handleHello(root: JsonObject) {
        val hello = root.getAsJsonObject("hello")
        ownSessionId = hello?.get("sessionid")?.asString ?: ""
        val features = hello?.getAsJsonObject("server")?.getAsJsonArray("features")
        val mcuActive = features?.any { it.asString == "mcu" } == true
        CallDebugLog.log(TAG, "hello mcu=$mcuActive features=$features")
        listener.onConnected(ownSessionId, mcuActive)
        sendRoomJoin()
    }

    private fun sendReady() {
        // The signaling room is joined; only now mark ourselves in-call via the OCS API so the
        // server's "participants/update" (in-call list) is delivered while we are listening.
        listener.onRoomJoined()
    }

    private fun handleEvent(root: JsonObject) {
        val event = root.getAsJsonObject("event") ?: return
        val target = event.get("target")?.asString
        val type = event.get("type")?.asString
        // Only "participants/update" carries the in-call participant list (with the "inCall" flag).
        // "room/join" is mere room/chat presence and must NOT trigger call peer connections.
        if (target != "participants" || type != "update") {
            CallDebugLog.log(TAG, "event target=$target type=$type (ignored)")
            return
        }
        var selfInCall = false
        val remotes = mutableListOf<String>()
        event.getAsJsonObject("update")?.getAsJsonArray("users")?.forEach { entry ->
            val user = entry.asJsonObject
            val inCall = (user.get("inCall")?.asInt ?: 0) != 0
            val session = user.get("sessionId")?.asString ?: user.get("sessionid")?.asString.orEmpty()
            if (!inCall || session.isBlank()) return@forEach
            if (session == ownSessionId) selfInCall = true else remotes.add(session)
        }
        CallDebugLog.log(TAG, "participants/update self=$selfInCall remotes=${remotes.size} raw=$event")
        if (selfInCall) listener.onSelfInCall()
        listener.onCallParticipants(remotes)
    }

    private fun handleMessage(root: JsonObject) {
        val message = root.getAsJsonObject("message") ?: return
        val from = message.getAsJsonObject("sender")?.get("sessionid")?.asString ?: return
        val data = message.getAsJsonObject("data") ?: return
        when (data.get("type")?.asString) {
            "offer" -> data.getAsJsonObject("payload")?.get("sdp")?.asString?.let { listener.onOffer(from, it) }

            "answer" -> data.getAsJsonObject("payload")?.get("sdp")?.asString?.let { listener.onAnswer(from, it) }

            "candidate" -> data.getAsJsonObject("payload")?.getAsJsonObject("candidate")
                ?.let { listener.onCandidate(from, it) }

            else -> Unit
        }
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempts = 0
            sendHello()
        }
        override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!closed) {
                if (code == NORMAL_CLOSURE) {
                    CallDebugLog.log(TAG, "server closed ws (code=$code reason=$reason) — call ended")
                    listener.onClosed()
                } else {
                    scheduleReconnect()
                }
            } else {
                listener.onClosed()
            }
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            CallDebugLog.log(TAG, "socket failure: ${t.message} httpCode=${response?.code}")
            scheduleReconnect()
        }
    }

    companion object {
        private const val TAG = "HpbSignaling"
        private const val ROOM_TYPE_VIDEO = "video"
        private const val NORMAL_CLOSURE = 1000
        private const val MAX_RECONNECTS = 5
        private const val RECONNECT_DELAY_MS = 1500L
        private const val PING_INTERVAL_SECONDS = 15L
    }
}
