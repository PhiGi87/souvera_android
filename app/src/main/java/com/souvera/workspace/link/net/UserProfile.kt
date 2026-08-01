/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.net

/** A slim user profile fetched from Nextcloud OCS for in-app display. */
data class UserProfile(
    val userId: String,
    val displayName: String = "",
    val email: String = "",
    val phone: String = "",
    val address: String = "",
    val website: String = "",
    val twitter: String = "",
    val groups: List<String> = emptyList(),
    val quotaTotal: Long = 0L,
    val quotaUsed: Long = 0L
)
