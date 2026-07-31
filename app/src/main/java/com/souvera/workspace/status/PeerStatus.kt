/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.status

/** Presence of a chat peer as reported by the user_status API (status + last activity). */
data class PeerStatus(
    val status: UserStatusType,
    val lastActivity: Long = 0L
)
