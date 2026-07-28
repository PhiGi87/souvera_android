/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.db.entity

/** IMAP SPECIAL-USE (RFC 6154) role of a mailbox, or [REGULAR] for anything else. */
enum class MailboxKind {
    INBOX,
    SENT,
    DRAFTS,
    TRASH,
    JUNK,
    REGULAR
}
