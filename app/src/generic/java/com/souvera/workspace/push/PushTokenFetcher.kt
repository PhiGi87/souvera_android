/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.push

import android.content.Context

/**
 * generic (Google-free) variant: no Firebase, so there is no FCM token to fetch. Mail push is only
 * available in the gplay build; this no-op keeps [com.souvera.workspace.mail.ui.MailActivity]
 * flavor-agnostic.
 */
object PushTokenFetcher {

    @Suppress("UNUSED_PARAMETER")
    fun fetchAndRegister(context: Context, onToken: (String) -> Unit) = Unit
}
