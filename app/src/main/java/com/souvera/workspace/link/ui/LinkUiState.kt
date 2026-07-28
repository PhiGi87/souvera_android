/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.ui

/** Loading/content/error state for a Link screen's data. */
sealed interface LinkUiState<out T> {
    data object Loading : LinkUiState<Nothing>
    data class Success<T>(val data: T) : LinkUiState<T>
    data class Error(val message: String) : LinkUiState<Nothing>
}
