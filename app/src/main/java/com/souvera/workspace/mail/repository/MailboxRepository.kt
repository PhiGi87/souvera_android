/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.repository

import android.content.Context
import com.souvera.workspace.dav.DavAccount
import com.souvera.workspace.mail.db.SouveraMailDatabase
import com.souvera.workspace.mail.db.entity.MailboxEntity
import com.souvera.workspace.mail.db.entity.MailboxKind
import com.souvera.workspace.mail.net.jmap.JmapApi
import com.souvera.workspace.mail.net.jmap.JmapClient
import com.souvera.workspace.mail.net.jmap.JmapMapper
import kotlinx.coroutines.flow.Flow

class MailboxRepository(context: Context) {

    private val db = SouveraMailDatabase.getInstance(context)

    fun observeMailboxes(accountName: String): Flow<List<MailboxEntity>> =
        db.mailboxDao().observeMailboxes(accountName)

    suspend fun syncMailboxes(
        accountName: String,
        dav: DavAccount
    ): MailResult<List<MailboxEntity>> = mailCall("Mailbox sync failed") {
        val client = JmapClient(dav)
        val api = JmapApi(client)
        val accountId = client.refreshSession().primaryAccountId
        val list = api.getMailboxes(accountId)
        val entities = (0 until list.length()).mapNotNull { i ->
            list.optJSONObject(i)?.let { JmapMapper.mapMailbox(accountName, it) }
        }
        entities
    }

    suspend fun refreshMailboxCounts(accountName: String, dav: DavAccount) {
        try {
            val client = JmapClient(dav)
            val api = JmapApi(client)
            val accountId = client.refreshSession().primaryAccountId
            val list = api.getMailboxes(accountId)
            for (i in 0 until list.length()) {
                val json = list.optJSONObject(i) ?: continue
                val name = json.optString("name", "?")
                val path = name
                val id = "$accountName:$path"
                val unread = json.optInt("unreadEmails", 0)
                val total = json.optInt("totalEmails", 0)
                db.mailboxDao().updateCounts(id, unread, total)
            }
        } catch (_: Exception) { /* best-effort counts refresh */ }
    }
}
