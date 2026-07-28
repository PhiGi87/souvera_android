/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs an IMAP/SMTP [block] on the IO dispatcher and turns any failure into [MailResult.Failure].
 * Mail networking must map every error uniformly so the UI shows a message instead of crashing -
 * that is why the broad catch is intentional and centralised here instead of repeated per call.
 */
@Suppress("TooGenericExceptionCaught")
suspend fun <T> mailCall(errorMessage: String, block: suspend () -> T): MailResult<T> = withContext(Dispatchers.IO) {
    try {
        MailResult.Success(block())
    } catch (e: Exception) {
        MailResult.Failure("$errorMessage: ${e.message}", e)
    }
}
