/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.ui

import android.accounts.AccountManager
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
        ensureFullScreenIntentPermission()

        val colorScheme = viewThemeUtils.getColorScheme(this)
        findViewById<ComposeView>(R.id.link_compose_view).setContent {
            MaterialTheme(colorScheme = colorScheme) {
                LinkRoot(viewModel = viewModel, onOpenDrawer = { openDrawer() })
            }
        }
    }

    override fun getMenuItemId(): Int = R.id.nav_link

    private var fsiPrompted = false

    private fun ensureFullScreenIntentPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val prefs = getSharedPreferences(PREFS_FSI, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_FSI_GRANTED, false)) return
        val manager = getSystemService(android.app.NotificationManager::class.java)
        if (manager.canUseFullScreenIntent()) {
            prefs.edit().putBoolean(KEY_FSI_GRANTED, true).apply()
            return
        }
        if (fsiPrompted) return
        fsiPrompted = true
        prefs.edit().putBoolean(KEY_FSI_GRANTED, true).apply()
        runCatching {
            startActivity(android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                android.net.Uri.parse("package:$packageName")))
            Toast.makeText(this, R.string.link_fsi_permission_hint, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        highlightNavigationViewItem(R.id.nav_link)
        findViewById<View>(R.id.appbar)?.visibility = View.GONE
        // Check if user granted FSI permission during previous redirect
        val prefs = getSharedPreferences(PREFS_FSI, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_FSI_GRANTED, false) && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val manager = getSystemService(android.app.NotificationManager::class.java)
            if (manager.canUseFullScreenIntent()) {
                prefs.edit().putBoolean(KEY_FSI_GRANTED, true).apply()
            }
        }
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

    companion object {
        private const val PREFS_FSI = "souvera_link_fsi"
        private const val KEY_FSI_GRANTED = "fsi_granted"
    }
}
