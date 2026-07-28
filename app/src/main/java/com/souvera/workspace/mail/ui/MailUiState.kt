/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.ui

/** Loading/content/error state for a mail screen's data, driven by the repository. */
sealed interface MailUiState<out T> {
    data object Loading : MailUiState<Nothing>
    data class Success<T>(val data: T) : MailUiState<T>
    data class Error(val message: String) : MailUiState<Nothing>
}
