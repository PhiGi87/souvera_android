/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/** Switches between the Link conversation list and an open chat based on the current route. */
@Composable
fun LinkRoot(viewModel: LinkViewModel, onOpenDrawer: () -> Unit, onOpenSettings: () -> Unit) {
    val route by viewModel.route.collectAsState()
    when (val current = route) {
        is LinkRoute.Home -> ConversationListScreen(viewModel, onOpenDrawer, onOpenSettings)
        is LinkRoute.Chat -> ChatScreen(viewModel, current)
    }
}
