/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.call

import android.content.Context
import com.google.gson.JsonObject
import com.souvera.workspace.dav.DavAccount
import com.souvera.workspace.link.net.OcsApi
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/**
 * Orchestrates a single "Link" call. The Souvera server runs the High Performance Backend with an
 * MCU (Janus), so media is not negotiated peer-to-peer: the local participant publishes its stream
 * to the MCU (one "publisher" peer connection addressed to its own session) and requests a separate
 * "subscriber" peer connection per remote participant. The protocol mirrors Nextcloud Talk's
 * PeerConnectionWrapper — see [[souvera-link-talk]]. A direct 1:1 fallback is kept for servers
 * without an MCU. Runs on a background thread; WebRTC callbacks arrive on WebRTC threads.
 */
class CallSession(
    private val context: Context,
    private val dav: DavAccount,
    private val token: String,
    private val withVideo: Boolean,
    private val callbacks: Callbacks
) : HpbSignalingClient.Listener {

    interface Callbacks {
        fun onLocalVideo(track: VideoTrack)
        fun onRemoteVideo(track: VideoTrack)
        fun onRemoteConnected()
        fun onEnded()
    }

    private val api = OcsApi(dav)
    private val webRtc = WebRtcClient(context)
    private var signaling: HpbSignalingClient? = null
    private var localAudio: AudioTrack? = null
    private var localVideo: VideoTrack? = null
    private val peers = mutableMapOf<String, PeerConnection>()
    private val requestedOffers = mutableSetOf<String>()

    private var pendingIceServers: List<PeerConnection.IceServer> = emptyList()
    private var ownSessionId: String = ""
    private var mcuActive: Boolean = false

    val eglBaseContext get() = webRtc.eglBase.eglBaseContext

    fun start() {
        CallDebugLog.log(TAG, "start token=$token")
        val sessionId = api.joinRoom(token) ?: run {
            CallDebugLog.log(TAG, "joinRoom failed")
            return end()
        }
        CallDebugLog.log(TAG, "joinRoom ok ncSession=$sessionId")
        val settings = api.getSignalingSettings(token) ?: run {
            CallDebugLog.log(TAG, "getSignalingSettings failed")
            return end()
        }
        CallDebugLog.log(TAG, "settings external=${settings.hasExternalServer()} server=${settings.server}")
        localAudio = webRtc.createLocalAudioTrack()
        localVideo = if (withVideo) webRtc.createLocalVideoTrack()?.also { callbacks.onLocalVideo(it) } else null
        pendingIceServers = settings.iceServers()
        if (settings.hasExternalServer()) {
            signaling = HpbSignalingClient(settings, dav.baseUrl, token, sessionId, this).also { it.connect() }
        } else {
            CallDebugLog.log(TAG, "No external signaling server; 1:1 internal signaling not implemented")
        }
    }

    private var publisherCreated = false
    private var hadRemote = false

    override fun onConnected(ownSessionId: String, mcuActive: Boolean) {
        this.ownSessionId = ownSessionId
        this.mcuActive = mcuActive
        CallDebugLog.log(TAG, "signaling connected own=$ownSessionId mcu=$mcuActive")
    }

    override fun onRoomJoined() {
        val flags = if (withVideo) FLAGS_AUDIO_VIDEO else FLAGS_AUDIO_ONLY
        Thread {
            api.joinCall(token, flags)
            CallDebugLog.log(TAG, "joinCall sent flags=$flags (after room join)")
        }.start()
    }

    // Only once the server confirms our own session is in-call (via participants/update) may we
    // publish to the MCU; publishing earlier is rejected/ignored by Janus.
    override fun onSelfInCall() {
        if (!mcuActive || publisherCreated) return
        publisherCreated = true
        CallDebugLog.log(TAG, "createPublisher to own=$ownSessionId")
        val peer = peerFor(ownSessionId, addLocalTracks = true)
        peer.createOffer(
            SimpleSdpObserver(onCreate = { sdp ->
                peer.setLocalDescription(SimpleSdpObserver(), sdp)
                signaling?.sendOffer(ownSessionId, sdp.description)
            }),
            publisherConstraints()
        )
    }

    override fun onCallParticipants(inCallRemoteSessions: List<String>) {
        CallDebugLog.log(TAG, "callParticipants=$inCallRemoteSessions mcu=$mcuActive")
        // 1:1 call semantics: once a remote has joined, their leaving ends the call for us too.
        if (inCallRemoteSessions.isNotEmpty()) {
            hadRemote = true
        } else if (hadRemote) {
            CallDebugLog.log(TAG, "remote left, ending call")
            return hangup()
        }
        disposeAbsentPeers(inCallRemoteSessions)
        if (mcuActive) {
            inCallRemoteSessions.forEach { session ->
                if (requestedOffers.add(session)) {
                    CallDebugLog.log(TAG, "requestOffer from $session")
                    signaling?.sendRequestOffer(session)
                }
            }
            return
        }
        inCallRemoteSessions.forEach { session ->
            if (peers.containsKey(session)) return@forEach
            val peer = peerFor(session, addLocalTracks = true)
            if (ownSessionId > session) {
                CallDebugLog.log(TAG, "offering to $session")
                peer.createOffer(
                    SimpleSdpObserver(onCreate = { sdp ->
                        peer.setLocalDescription(SimpleSdpObserver(), sdp)
                        signaling?.sendOffer(session, sdp.description)
                    }),
                    receiveConstraints()
                )
            }
        }
    }

    private fun disposeAbsentPeers(inCallRemoteSessions: List<String>) {
        val gone = peers.keys.filter { it != ownSessionId && it !in inCallRemoteSessions }
        gone.forEach { session ->
            CallDebugLog.log(TAG, "peer left, dispose $session")
            runCatching { peers.remove(session)?.dispose() }
            requestedOffers.remove(session)
        }
    }

    override fun onOffer(fromSession: String, sdp: String) {
        CallDebugLog.log(TAG, "onOffer from=$fromSession")
        val peer = peerFor(fromSession, addLocalTracks = !mcuActive)
        peer.setRemoteDescription(
            SimpleSdpObserver(onSet = {
                peer.createAnswer(
                    SimpleSdpObserver(onCreate = { answer ->
                        peer.setLocalDescription(SimpleSdpObserver(), answer)
                        signaling?.sendAnswer(fromSession, answer.description)
                    }),
                    MediaConstraints()
                )
            }),
            SessionDescription(SessionDescription.Type.OFFER, sdp)
        )
    }

    override fun onAnswer(fromSession: String, sdp: String) {
        CallDebugLog.log(TAG, "onAnswer from=$fromSession")
        peers[fromSession]?.setRemoteDescription(
            SimpleSdpObserver(),
            SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    override fun onCandidate(fromSession: String, candidate: JsonObject) {
        val peer = peers[fromSession] ?: return
        peer.addIceCandidate(
            IceCandidate(
                candidate.get("sdpMid")?.asString,
                candidate.get("sdpMLineIndex")?.asInt ?: 0,
                candidate.get("candidate")?.asString
            )
        )
    }

    override fun onClosed() {
        if (ownSessionId.isNotEmpty()) {
            CallDebugLog.log(TAG, "signaling permanently closed, ending call")
            hangup()
        }
    }

    fun setMuted(muted: Boolean) {
        localAudio?.setEnabled(!muted)
    }

    fun setVideoEnabled(enabled: Boolean) {
        localVideo?.setEnabled(enabled)
    }

    val hasVideo get() = localVideo != null

    /** Upgrades an audio call to video: create the camera track, add it to the publisher, renegotiate. */
    fun enableVideo() {
        if (localVideo != null) {
            localVideo?.setEnabled(true)
            return
        }
        val track = webRtc.createLocalVideoTrack() ?: return
        localVideo = track
        callbacks.onLocalVideo(track)
        val publisher = peers[ownSessionId] ?: return
        runCatching { publisher.addTrack(track) }
        CallDebugLog.log(TAG, "enableVideo: renegotiating publisher")
        publisher.createOffer(
            SimpleSdpObserver(onCreate = { sdp ->
                publisher.setLocalDescription(SimpleSdpObserver(), sdp)
                signaling?.sendOffer(ownSessionId, sdp.description)
            }),
            receiveConstraints()
        )
    }

    @Volatile
    private var endedOnce = false

    fun hangup(endForAll: Boolean = false) {
        if (endedOnce) return
        endedOnce = true
        CallDebugLog.log(TAG, "hangup endForAll=$endForAll")
        // Notify the UI first so renderers detach and the screen closes immediately; the blocking
        // teardown (network leaveCall + native disposal) then runs on a background thread. Running
        // it inline on the UI thread froze/ANRed the app on hangup.
        callbacks.onEnded()
        Thread {
            // leaveCall FIRST, while signaling is still connected, so the server marks us out of the
            // call and pushes a participants/update to the other side (ends a 1:1 for both). Only
            // then tear the local stack down.
            runCatching { api.leaveCall(token, endForAll) }
            runCatching { signaling?.close() }
            peers.values.forEach { runCatching { it.dispose() } }
            peers.clear()
            runCatching { webRtc.dispose() }
        }.start()
    }

    private fun peerFor(session: String, addLocalTracks: Boolean): PeerConnection = peers.getOrPut(session) {
        val peer = webRtc.createPeerConnection(pendingIceServers, PeerObserver(session))
            ?: error("Cannot create peer connection")
        if (addLocalTracks) {
            localAudio?.let { peer.addTrack(it) }
            localVideo?.let { peer.addTrack(it) }
        }
        // Janus expects a "status" data channel on every MCU peer connection (publisher and
        // subscriber) for control/state messages.
        if (mcuActive) {
            runCatching { peer.createDataChannel("status", DataChannel.Init()) }
        }
        peer
    }

    private fun publisherConstraints() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
    }

    private fun receiveConstraints() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
    }

    private fun end() {
        callbacks.onEnded()
    }

    private inner class PeerObserver(private val session: String) : SimplePeerConnectionObserver() {
        // In a 1:1 call the remote leaving/dropping tears its ICE connection down; end the call for
        // us too so hanging up on one side reliably ends it for both, even if the server's
        // participants/update is delayed or missed.
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            if (session == ownSessionId) return
            if (state == PeerConnection.IceConnectionState.CLOSED ||
                state == PeerConnection.IceConnectionState.FAILED
            ) {
                CallDebugLog.log(TAG, "remote $session ice=$state, ending call")
                hangup()
            }
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            val json = JsonObject().apply {
                addProperty("candidate", candidate.sdp)
                addProperty("sdpMid", candidate.sdpMid)
                addProperty("sdpMLineIndex", candidate.sdpMLineIndex)
            }
            signaling?.sendCandidate(session, json)
        }

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            val kind = receiver?.track()?.kind()
            CallDebugLog.log(TAG, "onAddTrack from=$session kind=$kind")
            if (session != ownSessionId) callbacks.onRemoteConnected()
            (receiver?.track() as? VideoTrack)?.let { callbacks.onRemoteVideo(it) }
        }
    }

    companion object {
        private const val TAG = "CallSession"
        private const val FLAGS_AUDIO_ONLY = 3
        private const val FLAGS_AUDIO_VIDEO = 7
    }
}
