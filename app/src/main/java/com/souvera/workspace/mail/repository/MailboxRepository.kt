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
import com.souvera.workspace.mail.net.ImapFolderMapper
import com.souvera.workspace.mail.net.ImapNamespaceReader
import com.souvera.workspace.mail.net.MailSession
import com.souvera.workspace.mail.net.mailboxId
import jakarta.mail.Folder
import kotlinx.coroutines.flow.Flow
import org.eclipse.angus.mail.imap.IMAPFolder

class MailboxRepository(context: Context) {

    private val db = SouveraMailDatabase.getInstance(context)

    fun observeMailboxes(accountName: String): Flow<List<MailboxEntity>> = db.mailboxDao().observeMailboxes(accountName)

    /**
     * Fast structural sync: a single IMAP LIST, no per-folder STATUS round-trips. Counts and sync
     * state carry over from the cached rows so the UI can render immediately; call
     * [refreshMailboxCounts] afterwards to update the badges in the background.
     */
    suspend fun syncMailboxes(accountName: String, dav: DavAccount): MailResult<List<MailboxEntity>> =
        mailCall("Mailbox sync failed") {
            val store = MailSession(dav).openImapStore()
            val namespaces = ImapNamespaceReader.read(store)
            val previous = db.mailboxDao().getMailboxes(accountName).associateBy { it.id }
            val entities = store.defaultFolder.list("*")
                .filterIsInstance<IMAPFolder>()
                .filter { (it.type and Folder.HOLDS_MESSAGES) != 0 }
                .map {
                    ImapFolderMapper.toEntity(
                        accountName,
                        it,
                        namespaces,
                        dav.username,
                        previous[mailboxId(accountName, it.fullName)]
                    )
                }
                .distinctBy { "${it.namespaceType}:${it.path}" }
            db.mailboxDao().upsertAll(entities)
            db.mailboxDao().pruneRemoved(accountName, entities.map { it.id })
            entities
        }

    /** Slow pass: one STATUS per folder, streaming each result into the DB (observers update live). */
    suspend fun refreshMailboxCounts(accountName: String, dav: DavAccount) {
        runCatching {
            val store = MailSession(dav).openImapStore()
            db.mailboxDao().getMailboxes(accountName).forEach { mailbox ->
                runCatching {
                    val folder = store.getFolder(mailbox.path)
                    db.mailboxDao().updateCounts(
                        mailbox.id,
                        folder.unreadMessageCount.coerceAtLeast(0),
                        folder.messageCount.coerceAtLeast(0)
                    )
                }
            }
        }
    }
}
