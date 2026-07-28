/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.call

import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/** [SdpObserver] with no-op defaults; supply [onCreate] (createOffer/Answer) or [onSet] as needed. */
class SimpleSdpObserver(private val onCreate: (SessionDescription) -> Unit = {}, private val onSet: () -> Unit = {}) :
    SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) = onCreate(sdp)
    override fun onSetSuccess() = onSet()
    override fun onCreateFailure(error: String?) = Unit
    override fun onSetFailure(error: String?) = Unit
}
