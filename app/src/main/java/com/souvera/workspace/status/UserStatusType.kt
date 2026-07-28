/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.status

import androidx.compose.ui.graphics.Color

/** The four user-status states offered by the Nextcloud user_status API. */
enum class UserStatusType(val apiValue: String, val dotColor: Color) {
    ONLINE("online", Color(0xFF49B382)),
    AWAY("away", Color(0xFFF4A100)),
    DND("dnd", Color(0xFFE9322D)),
    INVISIBLE("invisible", Color(0xFF9E9E9E));

    companion object {
        fun fromApi(value: String?): UserStatusType = entries.firstOrNull { it.apiValue == value } ?: INVISIBLE
    }
}
