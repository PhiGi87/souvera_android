/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.call

import android.accounts.AccountManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.owncloud.android.R
import com.souvera.workspace.dav.SouveraSyncManager
import com.souvera.workspace.link.net.OcsApi

/**
 * Handles the voice/inline reply to a Link chat notification (used by Android Auto and the
 * notification quick-reply). Sends the typed/spoken text to the conversation via the OCS chat API
 * on a background thread, then dismisses the notification.
 */
class LinkReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
        val nid = intent.getIntExtra(EXTRA_NID, 0)
        if (reply.isNullOrBlank()) return
        val appContext = context.applicationContext
        Thread {
            val account = AccountManager.get(appContext)
                .getAccountsByType(appContext.getString(R.string.account_type)).firstOrNull()
            val dav = account?.let { SouveraSyncManager(appContext).resolve(it) }
            if (dav != null) runCatching { OcsApi(dav).sendMessage(token, reply) }
            if (nid != 0) {
                (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(nid)
            }
        }.start()
    }

    companion object {
        const val EXTRA_TOKEN = "link_reply_token"
        const val EXTRA_NID = "link_reply_nid"
        const val EXTRA_ACCOUNT = "link_reply_account"
        const val KEY_REPLY = "link_reply_text"
    }
}
