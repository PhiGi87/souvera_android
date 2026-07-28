/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/** Switches between the mail screens based on the [MailViewModel]'s current [MailRoute]. */
@Composable
fun MailRoot(viewModel: MailViewModel, onOpenDrawer: () -> Unit, onOpenSettings: () -> Unit) {
    val route by viewModel.route.collectAsState()
    when (val current = route) {
        is MailRoute.Home -> MailHomeScreen(viewModel, onOpenDrawer, onOpenSettings)
        is MailRoute.Search -> MailSearchScreen(viewModel)
        is MailRoute.Detail -> MessageDetailScreen(viewModel, current.message)
        is MailRoute.Compose -> ComposeMessageScreen(viewModel, current)
    }
}
