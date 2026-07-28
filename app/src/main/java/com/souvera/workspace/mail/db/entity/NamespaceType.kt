/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.db.entity

/**
 * RFC 2342 IMAP namespace a mailbox belongs to. Stalwart exposes the user's own folders as
 * [PERSONAL], role/team mailboxes under the "shared" namespace as [SHARED], and delegated access to
 * another person's mailbox under the "other users" namespace as [OTHER_USERS]. Shared and
 * other-users mailboxes are grouped by [MailboxEntity.ownerIdentity] in the folder list.
 */
enum class NamespaceType {
    PERSONAL,
    SHARED,
    OTHER_USERS
}
