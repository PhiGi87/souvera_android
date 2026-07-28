/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.net

import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore
import org.eclipse.angus.mail.imap.protocol.IMAPProtocol
import org.eclipse.angus.mail.imap.protocol.Namespaces

/**
 * Reads the IMAP NAMESPACE command (RFC 2342) from an open [IMAPStore] via the angus-mail protocol
 * callback. Stalwart's shared/other-users prefixes are operator-configurable, so they are read per
 * session instead of hard-coded. Returns [MailNamespaces.EMPTY] if the server does not advertise
 * NAMESPACE, in which case every folder is treated as personal.
 */
object ImapNamespaceReader {

    fun read(store: IMAPStore): MailNamespaces {
        val root = store.defaultFolder as? IMAPFolder
        val result = root?.let { folder ->
            runCatching {
                folder.doCommand { protocol -> (protocol as IMAPProtocol).namespace() } as? Namespaces
            }.getOrNull()
        }
        return result?.let {
            MailNamespaces(
                personal = it.personal.toPrefixes(),
                otherUsers = it.otherUsers.toPrefixes(),
                shared = it.shared.toPrefixes()
            )
        } ?: MailNamespaces.EMPTY
    }

    private fun Array<Namespaces.Namespace>?.toPrefixes(): List<NamespacePrefix> =
        this?.map { NamespacePrefix(it.prefix ?: "", it.delimiter) } ?: emptyList()
}
