/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Hosts the server-side "souvera_mail" app (SnappyMail-based webmail) in an authenticated
 * WebView, as a drawer-hosted home screen: it carries the full navigation drawer (Files,
 * Calendar, Notes, ...) so Mail can be the app's main entry point while everything else stays
 * one tap away. The server's /embed route renders SnappyMail without the Nextcloud web shell;
 * the account app-password is sent as HTTP Basic auth on the first request so the server sets
 * its session cookie and no second login is needed (works with OIDC too).
 */
package com.souvera.workspace.mail

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import com.owncloud.android.R
import com.owncloud.android.ui.activity.DrawerActivity
import com.owncloud.android.utils.WebViewUtil
import com.souvera.workspace.dav.SouveraSyncManager

class SouveraMailWebViewActivity : DrawerActivity() {

    private lateinit var webView: WebView
    private lateinit var overlay: View

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_souvera_mail)

        setupToolbarShowOnlyMenuButtonAndTitle(getString(R.string.drawer_item_mail)) { openDrawer() }
        setupDrawer(R.id.nav_mail)

        overlay = findViewById(R.id.mail_overlay)
        webView = findViewById(R.id.mail_webview)

        val account = AccountManager.get(this)
            .getAccountsByType(getString(R.string.account_type))
            .firstOrNull()
        val dav = account?.let { SouveraSyncManager(this).resolve(it) }
        if (dav == null) {
            Toast.makeText(this, R.string.souvera_no_account, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        configureWebView()
        registerBackHandler()

        val url = "${dav.baseUrl}/index.php/apps/$MAIL_APP_ID/embed"
        val credentials = "${dav.username}:${dav.password}".toByteArray(Charsets.UTF_8)
        val authHeader = "Basic " + Base64.encodeToString(credentials, Base64.NO_WRAP)
        webView.loadUrl(url, mapOf("Authorization" to authHeader))
    }

    override fun onResume() {
        super.onResume()
        highlightNavigationViewItem(R.id.nav_mail)
    }

    override fun getMenuItemId(): Int = R.id.nav_mail

    private fun configureWebView() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            // Keep the native (Chrome-based) user agent: SnappyMail's browser detection
            // rejects the app's custom Nextcloud user agent as "unsupported".
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                overlay.postDelayed({ overlay.visibility = View.GONE }, OVERLAY_HIDE_DELAY_MS)
            }
        }
        WebViewUtil().setProxyKKPlus(webView)
    }

    private fun registerBackHandler() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val MAIL_APP_ID = "souvera_mail"
        private const val OVERLAY_HIDE_DELAY_MS = 350L
    }
}
