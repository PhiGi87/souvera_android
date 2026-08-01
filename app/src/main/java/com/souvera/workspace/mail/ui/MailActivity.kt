/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.ui

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.owncloud.android.R
import com.owncloud.android.ui.activity.DrawerActivity
import com.owncloud.android.ui.activity.SettingsActivity
import com.souvera.workspace.push.MailPushNotifier
import com.souvera.workspace.push.PushTokenFetcher

/**
 * Native mail client home screen: a [DrawerActivity] (so Files/Calendar/Notes stay one tap away)
 * hosting a Jetpack Compose tree. All chrome is Compose - the legacy XML toolbar is hidden and the
 * search bar carries the drawer button instead. Window insets are handled at the view root
 * (decorFitsSystemWindows=false + an OnApplyWindowInsetsListener that pads the content by the
 * keyboard/navigation-bar height): the whole screen resizes above the IME on every Android
 * version, independent of how insets propagate through the legacy XML/Compose hierarchy.
 */
class MailActivity : DrawerActivity() {

    private val viewModel: MailViewModel by viewModels()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_souvera_mail_native)

        setupToolbarShowOnlyMenuButtonAndTitle(getString(R.string.drawer_item_mail)) { openDrawer() }
        setupDrawer(R.id.nav_mail)
        findViewById<View>(R.id.appbar)?.visibility = View.GONE
        installInsetHandling()

        val accounts = AccountManager.get(this).getAccountsByType(getString(R.string.account_type))
        // Prefer the account a push notification was for; fall back to the first one.
        val pushAccountName = intent?.getStringExtra(MailPushNotifier.EXTRA_ACCOUNT_NAME)
        val account = accounts.firstOrNull { it.name == pushAccountName } ?: accounts.firstOrNull()
        if (account == null) {
            Toast.makeText(this, R.string.souvera_no_account, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        viewModel.start(account)
        enableBackgroundSync(account)
        registerForPush()
        requestNotificationPermission()
        registerBackHandler()

        handlePushDeepLink(intent)
        consumePushExtras(intent)

        val colorScheme = viewThemeUtils.getColorScheme(this)
        themeSystemBars(colorScheme.surface.toArgb())
        findViewById<ComposeView>(R.id.mail_compose_view).setContent {
            MaterialTheme(colorScheme = colorScheme) {
                MailRoot(
                    viewModel = viewModel,
                    onOpenDrawer = { openDrawer() },
                    onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) }
                )
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

    /**
     * Push deep link: open the exact mail the notification pointed at
     * (mailbox path + UID extras from MailPushNotifier).
     */
    private fun handlePushDeepLink(intent: Intent?) {
        val mailboxPath = intent?.getStringExtra(MailPushNotifier.EXTRA_MAILBOX_PATH)
        val emailId = intent?.getStringExtra(MailPushNotifier.EXTRA_MAIL_ID)
        if (!mailboxPath.isNullOrBlank() && !emailId.isNullOrBlank()) {
            viewModel.openMessageByEmailId(mailboxPath, emailId)
        }
    }

    /** Removes push extras so a configuration recreation does not replay the deep link. */
    private fun consumePushExtras(intent: Intent?) {
        if (intent == null) return
        intent.removeExtra(MailPushNotifier.EXTRA_MAILBOX_PATH)
        intent.removeExtra(MailPushNotifier.EXTRA_MAIL_ID)
        intent.removeExtra(MailPushNotifier.EXTRA_ACCOUNT_NAME)
    }

    override fun onResume() {
        super.onResume()
        highlightNavigationViewItem(R.id.nav_mail)
        findViewById<View>(R.id.appbar)?.visibility = View.GONE
        viewModel.refresh()
    }

    override fun getMenuItemId(): Int = R.id.nav_mail

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

    /**
     * Registers the FCM token with the souvera_mail broker (mail notifications). The standard
     * Nextcloud push v2 registration for Talk/Link + general notifications (via our own proxy in
     * push_server_url) is driven by NCFirebaseMessagingService.onNewToken, which has the proper
     * dependency-injection context.
     */
    private fun registerForPush() {
        android.util.Log.d("LinkPush", "Push proxy: ${getString(R.string.push_server_url)}")
        PushTokenFetcher.fetchAndRegister(this) { }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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

    private fun enableBackgroundSync(account: Account) {
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        listOf(getString(R.string.authority), CalendarContract.AUTHORITY, ContactsContract.AUTHORITY)
            .forEach { authority ->
                ContentResolver.setIsSyncable(account, authority, 1)
                ContentResolver.setSyncAutomatically(account, authority, true)
                ContentResolver.requestSync(account, authority, extras)
            }
    }

    private fun themeSystemBars(surfaceColor: Int) {
        @Suppress("DEPRECATION")
        window.statusBarColor = surfaceColor
        val nightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !nightMode
    }
}
