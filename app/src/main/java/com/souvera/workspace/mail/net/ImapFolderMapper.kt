/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.net

import com.souvera.workspace.mail.db.entity.MailboxEntity
import com.souvera.workspace.mail.db.entity.MailboxKind
import com.souvera.workspace.mail.db.entity.NamespaceType
import org.eclipse.angus.mail.imap.IMAPFolder

/**
 * Maps an opened [IMAPFolder] to its local cache row, namespace-aware. Stalwart only sets SPECIAL-USE
 * attributes (\Sent, \Trash, …) for the mailbox owner, so for shared/other-users mailboxes the role
 * is recovered from the (always-English) leaf name. The owner identity of a shared mailbox is the
 * first path segment after the namespace prefix, used to group folders in the UI.
 *
 * Performs NO per-folder network reads: unread/message counts and uidValidity each cost a
 * sequential IMAP STATUS round-trip, which across many shared mailboxes made the first sync take
 * minutes. Counts carry over from [previous] and are refreshed asynchronously by
 * MailboxRepository.refreshMailboxCounts.
 */
object ImapFolderMapper {

    fun toEntity(
        accountName: String,
        folder: IMAPFolder,
        namespaces: MailNamespaces,
        userIdentity: String? = null,
        previous: MailboxEntity?
    ): MailboxEntity {
        val fullName = folder.fullName
        val namespaceType = namespaces.classify(fullName, userIdentity)
        return MailboxEntity(
            id = mailboxId(accountName, fullName),
            accountName = accountName,
            name = folder.name,
            path = fullName,
            kind = kindOf(folder, namespaceType),
            namespaceType = namespaceType,
            ownerIdentity = ownerIdentity(namespaceType, fullName, namespaces),
            unreadCount = previous?.unreadCount ?: 0,
            messageCount = previous?.messageCount ?: 0,
            uidValidity = previous?.uidValidity ?: 0L,
            lastSeenUid = previous?.lastSeenUid ?: 0L
        )
    }

    private fun ownerIdentity(type: NamespaceType, fullName: String, namespaces: MailNamespaces): String? {
        if (type == NamespaceType.PERSONAL) return null
        namespaces.prefixFor(type, fullName)?.let { prefix ->
            val withoutPrefix = fullName.removePrefix(prefix.prefix)
            // If the prefix did not end with the delimiter (e.g. "Shared Folders" without
            // trailing "/"), removePrefix leaves a leading delimiter that must be stripped:
            // "Shared Folders/admins@host-on.de/INBOX" → "/admins@host-on.de/INBOX".
            val clean = withoutPrefix.removePrefix(prefix.delimiter.toString())
            clean.substringBefore(prefix.delimiter).takeIf { it.isNotBlank() }
        }?.let { return it }
        // Fallback: first segment IS the owner (e.g. "info@host-on.de/INBOX")
        val first = fullName.substringBefore('/')
        return first.takeIf { it.contains("@") && !it.equals("INBOX", ignoreCase = true) }
    }

    private fun Set<String>.hasAttribute(name: String): Boolean =
        any { it.equals(name, ignoreCase = true) }

    private fun kindOf(folder: IMAPFolder, namespaceType: NamespaceType): MailboxKind {
        val attributes = folder.attributes.toSet()
        val leaf = folder.name.trim()
        val fullName = folder.fullName
        // Leaf-name heuristic is reliable for shared/other-users mailboxes (Stalwart omits
        // SPECIAL-USE there) and for single-segment personal folders. Multi-segment personal
        // paths (e.g. "Deleted Items/info@host-on.de/Sent") must rely on server attributes
        // only to avoid duplicate Sent/Drafts/Trash/Junk entries in the personal group.
        val leafHeuristic = namespaceType != NamespaceType.PERSONAL || fullName.indexOf('/') < 0
        return when {
            fullName.equals("INBOX", ignoreCase = true) -> MailboxKind.INBOX
            namespaceType != NamespaceType.PERSONAL && leaf.equals("INBOX", ignoreCase = true) -> MailboxKind.INBOX
            attributes.hasAttribute("\\Sent") || (leafHeuristic && leaf.matchesAny(sentNames)) -> MailboxKind.SENT
            attributes.hasAttribute("\\Drafts") || (leafHeuristic && leaf.matchesAny(draftsNames)) -> MailboxKind.DRAFTS
            attributes.hasAttribute("\\Trash") || (leafHeuristic && leaf.matchesAny(trashNames)) -> MailboxKind.TRASH
            attributes.hasAttribute("\\Junk") || (leafHeuristic && leaf.matchesAny(junkNames)) -> MailboxKind.JUNK
            else -> MailboxKind.REGULAR
        }
    }

    private fun String.matchesAny(names: Set<String>): Boolean = names.any { it.equals(this, ignoreCase = true) }

    private val sentNames = setOf("Sent", "Sent Items", "Gesendet", "Gesendete Elemente")
    private val draftsNames = setOf("Drafts", "Entwürfe")
    private val trashNames = setOf("Trash", "Deleted Items", "Papierkorb", "Gelöschte Elemente")
    private val junkNames = setOf("Junk", "Spam", "Junk E-Mail", "Junk-E-Mail")
}
