/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.push

import android.accounts.AccountManager
import android.content.Context
import android.util.Log
import com.owncloud.android.R
import com.souvera.workspace.dav.SouveraSyncManager
import com.souvera.workspace.mail.SouveraMailCredentialManager
import com.souvera.workspace.mail.SouveraMailLoginFlow
import com.souvera.workspace.mail.net.MailSession
import com.souvera.workspace.mail.net.MimeBodyExtractor
import jakarta.mail.Folder
import jakarta.mail.internet.InternetAddress
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Privacy-preserving mail-preview source: the "new mail" push carries NO content (by backend
 * design), so when it arrives the app itself fetches the newest INBOX message over IMAP and builds
 * the sender/subject/snippet locally. Nothing sensitive ever passes through FCM.
 *
 * The entire operation is bounded by [FETCH_TIMEOUT_MS] (below FCM's ~10 s processing window) so a
 * hung IMAP connection cannot block the push handler indefinitely. On timeout or any other failure
 * the method returns null and the caller falls back to a generic "Neue E-Mail" notification.
 *
 * Uses [SouveraMailCredentialManager] to ensure the combined IMAP password is available (the
 * sync-manager path may fail if the DAV account hasn't been provisioned yet). Runs on the push
 * handler's background thread.
 */
object MailPushContentFetcher {

    private const val TAG = "MailPushContentFetch"

    /**
     * Maximum time the entire fetch (credential resolution + IMAP open + folder select + message
     * fetch + body parse) may take. FCM delivers push handlers on a background thread with a ~10 s
     * processing guarantee; staying well under that avoids the system killing us mid-operation.
     */
    private const val FETCH_TIMEOUT_MS = 8_000L

    private const val SNIPPET_LENGTH = 160
    private val HTML_TAGS = Regex("<[^>]+>")
    private val WHITESPACE = Regex("\\s+")

    data class MailPreview(val sender: String, val subject: String, val snippet: String)

    /**
     * Opens a fresh IMAP connection to INBOX, reads the most recent message, and returns a preview
     * with sender, subject, and a 160-char snippet of the body. Returns null on any failure
     * (timeout, no account, no credential, empty inbox, network error) — the caller must handle
     * the null case by showing a generic fallback notification.
     */
    fun fetchLatest(context: Context): MailPreview? {
        val startNanos = System.nanoTime()

        val manager = AccountManager.get(context)
        val account = manager.getAccountsByType(context.getString(R.string.account_type)).firstOrNull()
        if (account == null) {
            Log.w(TAG, "No Souvera account found — cannot fetch IMAP preview")
            return null
        }

        val dav = resolveCredentials(context, manager, account)
        if (dav == null) {
            Log.w(TAG, "Could not resolve IMAP credentials — cannot fetch preview")
            return null
        }

        return runCatching {
            // Bound the entire IMAP operation to stay within FCM's processing window.
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(FETCH_TIMEOUT_MS) {
                    openAndFetchImap(dav)
                }
            }
        }.onFailure { e ->
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
            Log.w(TAG, "IMAP preview failed after ${elapsedMs}ms: ${e.message}", e)
        }.getOrNull()
    }

    /**
     * Resolves the IMAP credential from the stored mail password (fast path) or by minting a fresh
     * combined app-password via the credential manager (slow path, needs network). Times out after
     * [CREDENTIAL_TIMEOUT_MS] to prevent blocking the push handler.
     */
    private fun resolveCredentials(
        context: Context,
        manager: AccountManager,
        account: android.accounts.Account
    ): com.souvera.workspace.dav.DavAccount? {
        val dav = SouveraSyncManager(context).resolve(account) ?: run {
            Log.w(TAG, "SouveraSyncManager.resolve returned null")
            return null
        }

        val storedMailPw = manager.getUserData(account, SouveraMailLoginFlow.ACCOUNT_KEY_MAIL_PASSWORD)
        if (!storedMailPw.isNullOrBlank()) {
            Log.d(TAG, "Using stored mail password for IMAP preview")
            return dav.copy(password = storedMailPw)
        }

        return try {
            Log.d(TAG, "No stored mail password — fetching combined credential")
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(CREDENTIAL_TIMEOUT_MS) {
                    SouveraMailCredentialManager(context).ensureCombinedCredential(account)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "CredentialManager failed: ${e.message}")
            dav
        }
    }

    private fun openAndFetchImap(dav: com.souvera.workspace.dav.DavAccount): MailPreview? {
        val store = MailSession(dav).openImapStore()
        return try {
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)
            try {
                val count = inbox.messageCount
                if (count < 1) {
                    Log.d(TAG, "INBOX is empty — no preview")
                    null
                } else {
                    val message = inbox.getMessage(count)
                    val from = message.from?.firstOrNull() as? InternetAddress
                    val sender = from?.personal?.takeIf { it.isNotBlank() } ?: from?.address.orEmpty()
                    val subject = message.subject.orEmpty()
                    val body = MimeBodyExtractor.extract(message)
                    val text = body.plainText ?: body.html?.replace(HTML_TAGS, " ")
                    val snippet =
                        text?.replace(WHITESPACE, " ")?.trim()?.take(SNIPPET_LENGTH).orEmpty()
                    Log.d(TAG, "Preview fetched: sender=\"$sender\" subject=\"$subject\" snippetLen=${snippet.length}")
                    MailPreview(sender, subject, snippet)
                }
            } finally {
                runCatching { inbox.close(false) }
            }
        } finally {
            runCatching { store.close() }
        }
    }

    private const val CREDENTIAL_TIMEOUT_MS = 5_000L
}
