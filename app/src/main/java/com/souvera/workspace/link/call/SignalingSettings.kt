/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.call

import com.google.gson.annotations.SerializedName
import org.webrtc.PeerConnection

/**
 * Talk `signaling/settings` response: the external (HPB) signaling server + auth ticket and the
 * ICE (STUN/TURN) servers to feed WebRTC. [iceServers] maps directly to org.webrtc IceServers.
 */
data class SignalingSettings(
    @SerializedName("signalingMode") val signalingMode: String = "",
    @SerializedName("server") val server: String = "",
    @SerializedName("ticket") val ticket: String = "",
    @SerializedName("userId") val userId: String = "",
    @SerializedName("stunservers") val stunServers: List<StunServer> = emptyList(),
    @SerializedName("turnservers") val turnServers: List<TurnServer> = emptyList()
) {
    fun iceServers(): List<PeerConnection.IceServer> {
        val stun = stunServers.flatMap { it.urls }.map { PeerConnection.IceServer.builder(it).createIceServer() }
        val turn = turnServers.map { turn ->
            PeerConnection.IceServer.builder(turn.urls)
                .setUsername(turn.username)
                .setPassword(turn.credential)
                .createIceServer()
        }
        return stun + turn
    }

    fun hasExternalServer(): Boolean = server.isNotBlank()
}

data class StunServer(@SerializedName("urls") val urls: List<String> = emptyList())

data class TurnServer(
    @SerializedName("urls") val urls: List<String> = emptyList(),
    @SerializedName("username") val username: String = "",
    @SerializedName("credential") val credential: String = ""
)
