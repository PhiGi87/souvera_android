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
        val session = client.refreshSession()
        // Session-ID verwenden (z. B. base32 "f") — live verifiziert.
        val accountId = session.primaryAccountId
        val entities = mutableListOf<MailboxEntity>()

        // Primary (personal) mailboxes.
        val personalList = api.getMailboxes(accountId)
        for (i in 0 until personalList.length()) {
            personalList.optJSONObject(i)?.let {
                entities.add(JmapMapper.mapMailbox(accountName, it))
            }
        }

        // Shared mailboxes: accounts in the session with isPersonal=false.
        client.getSessionJson()?.optJSONObject("accounts")?.let { accounts ->
            for (key in accounts.keys()) {
                val acc = accounts.optJSONObject(key) ?: continue
                val isPersonal = acc.optBoolean("isPersonal", true)
                val accName = acc.optString("name", null) ?: continue
                if (isPersonal || key == accountId || key == session.primaryAccountId) continue
                try {
                    val sharedList = api.getMailboxes(key)
                    for (i in 0 until sharedList.length()) {
                        sharedList.optJSONObject(i)?.let { json ->
                            val entity = JmapMapper.mapMailbox(accountName, json)
                            // Prefix path to distinguish from personal mailboxes.
                            val sharedEntity = entity.copy(
                                path = "$accName/${json.optString("name", entity.path)}",
                                id = "$accountName:$accName/${json.optString("name", entity.path)}",
                                namespaceType = com.souvera.workspace.mail.db.entity.NamespaceType.SHARED,
                                ownerIdentity = accName,
                                // Session-Account-Schluessel persistieren, damit
                                // Message-Sync/Body-Fetch den richtigen Account
                                // verwenden, statt ueber Name-Matching zu raten.
                                jmapAccountId = key,
                                // Shared-Unterordner haben KEINE Server-Rollen —
                                // Namensbasierte Systemordner-Zuordnung hier
                                // deaktivieren, sonst entstehen "2 Posteingaenge".
                                kind = com.souvera.workspace.mail.db.entity.MailboxKind.REGULAR
                            )
                            entities.add(sharedEntity)
                        }
                    }
                } catch (_: Exception) { /* shared mailbox might not be accessible */ }
            }
        }

        db.mailboxDao().upsertAll(entities)
        // Umbenannte/entfernte Ordner duerfen nicht als Geisterordner in der
        // App weiterleben (z. B. alte "Deleted Items"-Zeile nach Server-Fix).
        db.mailboxDao().pruneRemoved(accountName, entities.map { it.id })
        entities
    }

    /**
     * Löst den lokalen Pfad des Posteingangs auf. Die lokalen Mailbox-IDs
     * sind "$account:$name" (case-sensitive); Push-Payloads liefern aber
     * "INBOX" — daher hier role-basierte Normalisierung mit Namens-Fallback.
     */
    suspend fun resolveInboxPath(accountName: String): String? {
        val all = db.mailboxDao().getMailboxes(accountName)
        return all.firstOrNull { it.role == "inbox" }?.path
            ?: all.firstOrNull {
                it.name.equals("Inbox", ignoreCase = true)
            }?.path
    }

    suspend fun refreshMailboxCounts(accountName: String, dav: DavAccount) {
        try {
            val client = JmapClient(dav)
            val api = JmapApi(client)
            // Session-ID verwenden (live verifiziert).
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
