/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.call.telecom

import android.telecom.Connection
import android.telecom.DisconnectCause

/**
 * A self-managed Telecom [Connection] representing an active "Link" VoIP call. Registering the call
 * with the system integrates it with Bluetooth car kits / Android Auto: audio routes to the car and
 * the call can be ended from the car / steering wheel. The actual media stays in our WebRTC stack;
 * this only mirrors the call state to the OS. Car-side hang up is forwarded via [LinkTelecom].
 */
class LinkConnection : Connection() {

    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        audioModeIsVoip = true
        connectionCapabilities = CAPABILITY_MUTE or CAPABILITY_SUPPORT_HOLD
    }

    override fun onAnswer() {
        setActive()
    }

    override fun onDisconnect() {
        LinkTelecom.onCarHangup?.invoke()
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
        LinkTelecom.clear(this)
    }

    override fun onAbort() {
        setDisconnected(DisconnectCause(DisconnectCause.CANCELED))
        destroy()
        LinkTelecom.clear(this)
    }

    override fun onReject() {
        LinkTelecom.onCarHangup?.invoke()
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
        LinkTelecom.clear(this)
    }
}
