/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.repository

sealed class MailResult<out T> {
    data class Success<T>(val value: T) : MailResult<T>()
    data class Failure(val message: String, val cause: Throwable? = null) : MailResult<Nothing>()
}
