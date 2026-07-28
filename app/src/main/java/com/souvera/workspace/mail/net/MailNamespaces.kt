/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.net

import com.souvera.workspace.mail.db.entity.NamespaceType

data class NamespacePrefix(val prefix: String, val delimiter: Char)

data class MailNamespaces(
    val personal: List<NamespacePrefix> = emptyList(),
    val otherUsers: List<NamespacePrefix> = emptyList(),
    val shared: List<NamespacePrefix> = emptyList()
) {
    fun classify(fullName: String, userIdentity: String? = null): NamespaceType = when {
        matches(shared, fullName) -> NamespaceType.SHARED
        matches(otherUsers, fullName) -> NamespaceType.OTHER_USERS
        // Heuristic fallback ONLY when server reported NO shared/other-users namespace info
        shared.isEmpty() && otherUsers.isEmpty() && isSharedFolderByPattern(fullName, userIdentity) -> NamespaceType.SHARED
        else -> NamespaceType.PERSONAL
    }

    fun prefixFor(type: NamespaceType, fullName: String): NamespacePrefix? = when (type) {
        NamespaceType.SHARED -> shared
        NamespaceType.OTHER_USERS -> otherUsers
        NamespaceType.PERSONAL -> personal
    }.filter { matchesPrefixBoundary(it, fullName) }
        .maxByOrNull { it.prefix.length }

    /**
     * Heuristic fallback for servers that do NOT advertise NAMESPACE (RFC 2342) shared/other-users
     * prefixes. When the server reports proper namespace info — as Stalwart does with its
     * operator-configurable shared prefix — the IMAP NAMESPACE command is the authoritative source
     * and this heuristic is skipped entirely.
     * Pattern: "other@domain/INBOX" → shared, "INBOX" or "INBOX/Subfolder" → personal.
     */
    private fun isSharedFolderByPattern(fullName: String, userIdentity: String?): Boolean {
        val segments = fullName.split('/', limit = 2)
        if (segments.size < 2) return false
        val first = segments[0]
        // Root-level INBOX with subfolders is personal (e.g. INBOX/Administration)
        if (first.equals("INBOX", ignoreCase = true)) return false
        // First segment matches the logged-in user → personal, not shared
        if (userIdentity != null && first.equals(userIdentity, ignoreCase = true)) return false
        // First segment is a full email address → this is a shared mailbox
        return first.contains("@")
    }

    private fun matches(prefixes: List<NamespacePrefix>, fullName: String): Boolean =
        prefixes.any { matchesPrefixBoundary(it, fullName) }

    /**
     * Returns true when [fullName] lies within the namespace described by [prefix].  A prefix match
     * only counts when the name equals the prefix exactly, or when the character immediately after
     * the prefix is the namespace delimiter — this avoids false matches on prefixes that happen to
     * share a common stem (e.g. "Shared Folders" must not match "Shared FoldersBackup/…").
     */
    private fun matchesPrefixBoundary(prefix: NamespacePrefix, fullName: String): Boolean {
        if (prefix.prefix.isEmpty()) return false
        return fullName == prefix.prefix ||
            (fullName.startsWith(prefix.prefix) && fullName[prefix.prefix.length] == prefix.delimiter)
    }

    companion object {
        val EMPTY = MailNamespaces()
    }
}
