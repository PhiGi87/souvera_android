/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.call

import android.content.Context
import android.content.Intent

/** Signalisiert der App, dass ein Anruf serverseitig beendet wurde. */
object LinkCallEnd {
    const val ACTION_CALL_ENDED = "com.souvera.workspace.link.call.CALL_ENDED"

    fun broadcast(context: Context, token: String) {
        context.sendBroadcast(
            Intent(ACTION_CALL_ENDED)
                .setPackage(context.packageName)
                .putExtra(CallActivity.EXTRA_TOKEN, token)
        )
    }
}
