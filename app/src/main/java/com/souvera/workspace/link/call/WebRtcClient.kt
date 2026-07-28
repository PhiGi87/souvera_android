/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.call

import android.content.Context
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Owns the process-wide [PeerConnectionFactory] and the shared [EglBase] for the "Link" (Nextcloud
 * Talk) call stack, plus local audio/video capture. Built directly on the reused, hardened
 * `android-talk-webrtc` build (org.webrtc). One instance per active call; call [dispose] when the
 * call ends. Peer connections themselves are wrapped per remote participant in [PeerConnectionHolder].
 */
class WebRtcClient(private val context: Context) {

    val eglBase: EglBase = EglBase.create()

    private val factory: PeerConnectionFactory
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var capturer: org.webrtc.VideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun createPeerConnection(
        iceServers: List<PeerConnection.IceServer>,
        observer: PeerConnection.Observer
    ): PeerConnection? {
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        return factory.createPeerConnection(config, observer)
    }

    fun createLocalAudioTrack(): AudioTrack {
        val source = factory.createAudioSource(MediaConstraints())
        audioSource = source
        return factory.createAudioTrack(AUDIO_TRACK_ID, source)
    }

    fun createLocalVideoTrack(): VideoTrack? {
        val enumerator = Camera2Enumerator(context)
        val deviceName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: return null
        val videoCapturer = enumerator.createCapturer(deviceName, null) ?: return null
        capturer = videoCapturer
        val source = factory.createVideoSource(false)
        videoSource = source
        val helper = SurfaceTextureHelper.create("LinkCaptureThread", eglBase.eglBaseContext)
        surfaceHelper = helper
        videoCapturer.initialize(helper, context, source.capturerObserver)
        videoCapturer.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)
        return factory.createVideoTrack(VIDEO_TRACK_ID, source)
    }

    @Volatile
    private var disposed = false

    fun dispose() {
        if (disposed) return
        disposed = true
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { audioSource?.dispose() }
        runCatching { surfaceHelper?.dispose() }
        runCatching { factory.dispose() }
        runCatching { eglBase.release() }
        capturer = null
        videoSource = null
        audioSource = null
        surfaceHelper = null
    }

    companion object {
        private const val AUDIO_TRACK_ID = "link_audio0"
        private const val VIDEO_TRACK_ID = "link_video0"
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_FPS = 30
    }
}
