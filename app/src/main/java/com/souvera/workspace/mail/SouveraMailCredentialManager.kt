/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import com.souvera.workspace.dav.DavAccount
import com.souvera.workspace.dav.SouveraSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Provides the combined Nextcloud+Stalwart credential the native mail client needs for IMAP/SMTP,
 * WITHOUT touching the account password used for Files/CalDAV/CardDAV.
 *
 * The account password (`AccountManager.getPassword`) stays the plain Nextcloud app-password X that
 * WebDAV/DAV authenticate with. On first entry to the mail client this mints a SEPARATE combined
 * password Y (via `/login-flow`, which does not revoke X) and stores it under
 * [SouveraMailLoginFlow.ACCOUNT_KEY_MAIL_PASSWORD]; the returned [DavAccount] carries Y so only the
 * mail layer uses it. Overwriting the account password with Y previously broke the file sync,
 * because `/upgrade` revoked X and DAV then authenticated with a mail-only token (401 -> empty
 * file list). Failures are swallowed: DAV keeps working, only mail stays unavailable until retried.
 */
class SouveraMailCredentialManager(private val context: Context) {

    suspend fun ensureCombinedCredential(account: Account): DavAccount? = withContext(Dispatchers.IO) {
        val accountManager = AccountManager.get(context)
        val dav = SouveraSyncManager(context).resolve(account) ?: return@withContext null

        val storedMailPassword =
            accountManager.getUserData(account, SouveraMailLoginFlow.ACCOUNT_KEY_MAIL_PASSWORD)
        if (!storedMailPassword.isNullOrBlank()) {
            return@withContext dav.copy(password = storedMailPassword)
        }

        val combined = SouveraMailLoginFlow.fetchCombinedAppPassword(dav.baseUrl, dav.username, dav.password)
        accountManager.setUserData(account, SouveraMailLoginFlow.ACCOUNT_KEY_MAIL_PASSWORD, combined.appPassword)
        accountManager.setUserData(account, SouveraMailLoginFlow.ACCOUNT_KEY_STALWART_ID, combined.stalwartId)
        dav.copy(password = combined.appPassword)
    }
}
