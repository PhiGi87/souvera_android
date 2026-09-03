/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.call

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import com.google.gson.JsonObject
import com.souvera.workspace.dav.DavAccount
import com.souvera.workspace.link.net.OcsApi
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

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
        fun onRecovering(recovering: Boolean)
        fun onEnded()
    }

    private val api = OcsApi(dav)
    private val webRtc = WebRtcClient(context)
    private var signaling: HpbSignalingClient? = null
    private var localAudio: AudioTrack? = null
    private var localVideo: VideoTrack? = null
    private val peers = mutableMapOf<String, PeerConnection>()
    private val requestedOffers = mutableSetOf<String>()

    // Dedupe fuer Remote-Tracks: onAddTrack und onTrack (UNIFIED_PLAN, MCU-
    // Subscriber) koennen denselben Track melden; jeder Track wird nur einmal
    // an die UI gemeldet.
    private val reportedRemoteTracks = Collections.newSetFromMap(ConcurrentHashMap<MediaStreamTrack, Boolean>())
    @Volatile private var remoteConnectedNotified = false

    private var pendingIceServers: List<PeerConnection.IceServer> = emptyList()
    private var ownSessionId: String = ""
    private var mcuActive: Boolean = false

    // Netzwerk-Wechsel-Recovery: Peer-Neuaufbau wird über einen einzigen
    // Handler serialisiert; jeder Peer darf begrenzt oft neu starten.
    private val recoveryHandler = Handler(Looper.getMainLooper())
    private val peerFailures = mutableMapOf<String, Int>()
    private val recoveryRunnables = mutableMapOf<String, Runnable>()
    @Volatile private var recovering = false
    @Volatile private var signalingWasConnected = false
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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
        registerNetworkMonitor()
    }

    /**
     * Proaktive Netzwerküberwachung: Beim Wechsel des Default-Networks
     * (WLAN <-> Mobil) wird ICE auf allen aktiven Peers sofort neu gestartet,
     * statt auf WebSocket-/ICE-Timeouts zu warten.
     */
    private fun registerNetworkMonitor() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Erster Callback = aktuelle Verbindung; erst ein WECHSEL ist relevant.
                if (!signalingWasConnected) return
                CallDebugLog.log(TAG, "default network changed — restarting ICE on all peers")
                recoveryHandler.post { restartAllPeers("network-change") }
            }
        }
        networkCallback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }

    private fun unregisterNetworkMonitor() {
        runCatching { networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) } }
        networkCallback = null
        connectivityManager = null
    }

    private var publisherCreated = false
    private var hadRemote = false

    override fun onConnected(ownSessionId: String, mcuActive: Boolean) {
        val wasConnected = signalingWasConnected
        signalingWasConnected = true
        this.ownSessionId = ownSessionId
        this.mcuActive = mcuActive
        CallDebugLog.log(TAG, "signaling connected own=$ownSessionId mcu=$mcuActive reconnect=$wasConnected")
        if (wasConnected) {
            // Echter Reconnect: Alle Peers beruhen auf der alten Route und sind
            // ungültig. Komplett zurücksetzen; der nächste participants/update
            // und onSelfInCall bauen Publisher/Subscriber frisch auf.
            recoveryHandler.post {
                CallDebugLog.log(TAG, "signaling reconnected — resetting peer state")
                peers.values.forEach { runCatching { it.dispose() } }
                peers.clear()
                requestedOffers.clear()
                peerFailures.clear()
                recoveryRunnables.values.forEach { recoveryHandler.removeCallbacks(it) }
                recoveryRunnables.clear()
                publisherCreated = false
                hadRemote = false
                setRecovering(false)
            }
        }
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
        setRecovering(false)
        callbacks.onEnded()
        Thread {
            // leaveCall FIRST, while signaling is still connected, so the server marks us out of the
            // call and pushes a participants/update to the other side (ends a 1:1 for both). Only
            // then tear the local stack down.
            unregisterNetworkMonitor()
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

    /** ICE-Neustart für ALLE aktiven Peers (Netzwerk-Wechsel). */
    private fun restartAllPeers(reason: String) {
        if (endedOnce) return
        CallDebugLog.log(TAG, "restartAllPeers reason=$reason")
        peers.forEach { (session, peer) -> restartPeer(session, peer) }
    }

    /**
     * Startet ICE für einen Peer neu. Publisher wird mit einem frischen Offer
     * neu ausgehandelt; MCU-Subscriber bekommen ein neues Angebot über
     * requestoffer; im P2P-Fallback bietet die Seite mit der kleineren
     * Session-ID neu an.
     */
    private fun restartPeer(session: String, peer: PeerConnection) {
        if (endedOnce) return
        setRecovering(true)
        CallDebugLog.log(TAG, "restartPeer session=$session")
        runCatching { peer.restartIce() }
        if (session == ownSessionId) {
            peer.createOffer(
                SimpleSdpObserver(onCreate = { sdp ->
                    peer.setLocalDescription(SimpleSdpObserver(), sdp)
                    signaling?.sendOffer(session, sdp.description)
                }),
                publisherConstraints()
            )
        } else if (mcuActive) {
            requestedOffers.remove(session)
            signaling?.sendRequestOffer(session)
        } else if (ownSessionId > session) {
            peer.createOffer(
                SimpleSdpObserver(onCreate = { sdp ->
                    peer.setLocalDescription(SimpleSdpObserver(), sdp)
                    signaling?.sendOffer(session, sdp.description)
                }),
                receiveConstraints()
            )
        }
    }

    private fun scheduleRestart(session: String) {
        if (endedOnce) return
        val failures = (peerFailures[session] ?: 0) + 1
        peerFailures[session] = failures
        val peer = peers[session] ?: return
        if (failures > MAX_ICE_RESTARTS) {
            CallDebugLog.log(TAG, "peer $session failed $failures times — ending call")
            hangup()
            return
        }
        CallDebugLog.log(TAG, "peer $session restart attempt $failures")
        restartPeer(session, peer)
    }

    private fun setRecovering(value: Boolean) {
        if (recovering == value) return
        recovering = value
        CallDebugLog.log(TAG, "recovering=$value")
        runCatching { callbacks.onRecovering(value) }
    }

    private fun end() {
        callbacks.onEnded()
    }

    private inner class PeerObserver(private val session: String) : SimplePeerConnectionObserver() {
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            CallDebugLog.log(TAG, "peer $session ice=$state")
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    peerFailures.remove(session)
                    recoveryRunnables.remove(session)?.let { recoveryHandler.removeCallbacks(it) }
                    setRecovering(false)
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    // Kulanzfenster: Netzwerkschwankungen erst wirken lassen.
                    if (!recoveryRunnables.containsKey(session)) {
                        val runnable = Runnable {
                            CallDebugLog.log(TAG, "peer $session DISCONNECTED grace expired — restarting ICE")
                            recoveryHandler.post { scheduleRestart(session) }
                        }
                        recoveryRunnables[session] = runnable
                        recoveryHandler.postDelayed(runnable, ICE_RECOVERY_GRACE_MS)
                    }
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    recoveryHandler.post { scheduleRestart(session) }
                }
                PeerConnection.IceConnectionState.CLOSED -> {
                    // Remote hat den Call beendet (Subscriber wurde serverseitig
                    // abgebaut) — nur dann ist CLOSED ein echtes Call-Ende.
                    recoveryRunnables.remove(session)?.let { recoveryHandler.removeCallbacks(it) }
                    if (session != ownSessionId && !recovering) {
                        CallDebugLog.log(TAG, "remote $session closed — ending call")
                        hangup()
                    }
                }
                else -> Unit
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
            handleRemoteTrack(receiver?.track(), "onAddTrack")
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver?.track()
            CallDebugLog.log(
                TAG,
                "onTrack from=$session mid=${transceiver.mid} direction=${transceiver.direction} kind=${track?.kind()}"
            )
            handleRemoteTrack(track, "onTrack")
        }

        private fun handleRemoteTrack(track: MediaStreamTrack?, source: String) {
            if (session == ownSessionId) return
            if (!remoteConnectedNotified) {
                remoteConnectedNotified = true
                callbacks.onRemoteConnected()
            }
            val video = track as? VideoTrack ?: return
            if (!reportedRemoteTracks.add(video)) return
            CallDebugLog.log(TAG, "handleRemoteTrack from=$session source=$source kind=${video.kind()} id=${video.id()}")
            callbacks.onRemoteVideo(video)
        }
    }

    companion object {
        private const val TAG = "CallSession"
        private const val FLAGS_AUDIO_ONLY = 3
        private const val FLAGS_AUDIO_VIDEO = 7
        private const val ICE_RECOVERY_GRACE_MS = 4000L
        private const val MAX_ICE_RESTARTS = 3
    }
}
