/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.call

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Dismisses the incoming "Link" call notification when the user taps Decline. */
class CallDeclineReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val nid = intent.getIntExtra(EXTRA_NID, 0)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(nid)
    }

    companion object {
        const val ACTION_DECLINE = "com.souvera.workspace.link.call.DECLINE"
        const val EXTRA_NID = "nid"
    }
}
