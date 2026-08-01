/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.ui

import android.accounts.AccountManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
import com.owncloud.android.R
import com.owncloud.android.ui.activity.DrawerActivity

class LinkActivity : DrawerActivity() {

    private val viewModel: LinkViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.souvera.workspace.link.call.CallDebugLog.attach(this)
        setContentView(R.layout.activity_souvera_link)
        setupToolbarShowOnlyMenuButtonAndTitle(getString(R.string.drawer_item_link)) { openDrawer() }
        setupDrawer(R.id.nav_link)
        findViewById<View>(R.id.appbar)?.visibility = View.GONE

        val account = AccountManager.get(this)
            .getAccountsByType(getString(R.string.account_type))
            .firstOrNull()
        if (account == null) {
            Toast.makeText(this, R.string.souvera_no_account, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        viewModel.start(account)
        registerBackHandler()

        handlePushDeepLink(intent)

        val colorScheme = viewThemeUtils.getColorScheme(this)
        findViewById<ComposeView>(R.id.link_compose_view).setContent {
            MaterialTheme(colorScheme = colorScheme) {
                LinkRoot(viewModel = viewModel, onOpenDrawer = { openDrawer() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // SINGLE_TOP notification taps land here instead of onCreate.
        setIntent(intent)
        handlePushDeepLink(intent)
    }

    /** Push deep link: open the exact chat the notification pointed at. */
    private fun handlePushDeepLink(intent: Intent?) {
        intent?.getStringExtra(com.souvera.workspace.push.MailPushNotifier.EXTRA_CHAT_TOKEN)
            ?.takeIf { it.isNotBlank() }
            ?.let { token ->
                val title = intent?.getStringExtra(Intent.EXTRA_TITLE) ?: ""
                viewModel.openConversation(token, title)
            }
    }

    override fun getMenuItemId(): Int = R.id.nav_link

    override fun onResume() {
        super.onResume()
        highlightNavigationViewItem(R.id.nav_link)
        findViewById<View>(R.id.appbar)?.visibility = View.GONE
    }

    private fun registerBackHandler() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!viewModel.back()) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }
}