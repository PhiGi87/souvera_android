/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.call

import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver

/** [PeerConnection.Observer] with no-op defaults so call sites override only what they need. */
open class SimplePeerConnectionObserver : PeerConnection.Observer {
    override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
    override fun onIceCandidate(candidate: IceCandidate) = Unit
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
    override fun onAddStream(stream: MediaStream?) = Unit
    override fun onRemoveStream(stream: MediaStream?) = Unit
    override fun onDataChannel(channel: DataChannel?) = Unit
    override fun onRenegotiationNeeded() = Unit
    override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
}
