/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.ui

/** Progress of an outgoing message, kept separate from the read-side [MailUiState]. */
sealed interface SendState {
    data object Idle : SendState
    data object Sending : SendState
    data object Sent : SendState
    data class Error(val message: String) : SendState
}
