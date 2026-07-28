/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.ui

/** Which Link screen is shown: the conversation list or one open chat. */
sealed interface LinkRoute {
    data object Home : LinkRoute
    data class Chat(val token: String, val title: String) : LinkRoute
}
