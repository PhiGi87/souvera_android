/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.call.telecom

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.core.content.getSystemService
import com.souvera.workspace.link.call.CallDebugLog

/**
 * Registers "Link" calls with the Android Telecom framework as self-managed VoIP calls, so call
 * audio routes through connected Bluetooth car kits / Android Auto and the call can be answered or
 * ended from the car. Everything here is best-effort and guarded: if Telecom is unavailable or
 * throws, the call still runs on WebRTC with the in-app UI. See [[souvera-link-talk]].
 */
object LinkTelecom {

    const val EXTRA_CALLER = "souvera_link_caller"
    private const val TAG = "LinkTelecom"
    private const val ACCOUNT_ID = "souvera_link"

    @Volatile
    private var connection: LinkConnection? = null

    /** Invoked when the call is ended from the car/system so the app can tear the call down too. */
    @Volatile
    var onCarHangup: (() -> Unit)? = null

    private fun handle(context: Context) =
        PhoneAccountHandle(ComponentName(context, LinkConnectionService::class.java), ACCOUNT_ID)

    fun registerAccount(context: Context) {
        runCatching {
            val tm = context.getSystemService<TelecomManager>() ?: return
            val account = PhoneAccount.builder(handle(context), "Souvera Link")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .build()
            tm.registerPhoneAccount(account)
        }.onFailure { CallDebugLog.log(TAG, "registerAccount failed: ${it.message}") }
    }

    fun reportOngoingCall(context: Context, caller: String, incoming: Boolean, onHangup: () -> Unit) {
        onCarHangup = onHangup
        runCatching {
            val tm = context.getSystemService<TelecomManager>() ?: return
            val handle = handle(context)
            val address = Uri.fromParts("sip", "link", null)
            if (incoming) {
                val extras = Bundle().apply {
                    putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                    putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, address)
                    putString(EXTRA_CALLER, caller)
                }
                tm.addNewIncomingCall(handle, extras)
            } else {
                val extras = Bundle().apply {
                    putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                    putBundle(
                        TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS,
                        Bundle().apply {
                            putString(EXTRA_CALLER, caller)
                        }
                    )
                }
                tm.placeCall(address, extras)
            }
        }.onFailure { CallDebugLog.log(TAG, "reportOngoingCall failed: ${it.message}") }
    }

    fun register(connection: LinkConnection) {
        this.connection = connection
    }

    fun clear(connection: LinkConnection) {
        if (this.connection === connection) this.connection = null
    }

    /** Ends the Telecom call when the user hangs up in the app. */
    fun endCall() {
        onCarHangup = null
        runCatching {
            connection?.setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
            connection?.destroy()
        }
        connection = null
    }
}
