/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.call.telecom

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle

/**
 * Bridges the system Telecom stack to our [LinkConnection]. Telecom calls these when we report an
 * outgoing/incoming self-managed call via [LinkTelecom]; both simply return an already-active
 * connection so the OS/Bluetooth/Android Auto know a VoIP call is in progress.
 */
class LinkConnectionService : ConnectionService() {

    override fun onCreateOutgoingConnection(handle: PhoneAccountHandle?, request: ConnectionRequest?): Connection =
        newConnection(request)

    override fun onCreateIncomingConnection(handle: PhoneAccountHandle?, request: ConnectionRequest?): Connection =
        newConnection(request)

    private fun newConnection(request: ConnectionRequest?): LinkConnection {
        val connection = LinkConnection()
        request?.address?.let { connection.setAddress(it, android.telecom.TelecomManager.PRESENTATION_ALLOWED) }
        val caller = request?.extras?.getString(LinkTelecom.EXTRA_CALLER)
        if (!caller.isNullOrBlank()) {
            connection.setCallerDisplayName(
                caller,
                android.telecom.TelecomManager.PRESENTATION_ALLOWED
            )
        }
        connection.setActive()
        LinkTelecom.register(connection)
        return connection
    }
}
