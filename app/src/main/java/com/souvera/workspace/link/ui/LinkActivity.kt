/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.ui

import androidx.activity.enableEdgeToEdge
import android.accounts.AccountManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.updatePadding
import androidx.core.view.WindowInsetsCompat
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
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        // Nahtloser Übergang: Falls ein Gerät Edge-to-Edge nicht umsetzt,
        // färbt die Systemleiste exakt in der obersten Verlaufsfarbe —
        // so wirkt der Verlauf immer bis hinter die Uhr durchgezogen.
        @Suppress("DEPRECATION")
        window.statusBarColor = 0xFF1E4666.toInt()

        com.souvera.workspace.link.call.CallDebugLog.attach(this)
        setContentView(R.layout.activity_souvera_link)
        installInsetHandling()
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
        registerForChatPush()

        handlePushDeepLink(intent)
        consumePushExtras(intent)

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
        consumePushExtras(intent)
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

    /** Registers this device for chat push notifications (FCM via NC push proxy). */
    private fun registerForChatPush() {
        com.souvera.workspace.push.PushTokenFetcher.fetchAndRegister(this) { }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                registerForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
                ) { }.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /** Removes push extras so a configuration recreation does not replay the deep link. */
    private fun consumePushExtras(intent: Intent?) {
        if (intent == null) return
        intent.removeExtra(com.souvera.workspace.push.MailPushNotifier.EXTRA_CHAT_TOKEN)
        intent.removeExtra(Intent.EXTRA_TITLE)
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

    private fun installInsetHandling() {
        val root = findViewById<View>(R.id.drawer_layout)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bottomInset = maxOf(
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            )
            view.updatePadding(bottom = bottomInset)
            WindowInsetsCompat.Builder(insets)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.NONE)
                .build()
        }
    }
}
