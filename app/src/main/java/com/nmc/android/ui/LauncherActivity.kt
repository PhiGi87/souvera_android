/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2023 Alper Ozturk <alper.ozturk@nextcloud.com>
 * SPDX-FileCopyrightText: 2023 Andy Scherzinger <info@andy-scherzinger.de>
 * SPDX-FileCopyrightText: 2023-2024 TSI-mc <surinder.kumar@t-systems.com>
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.nmc.android.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.nextcloud.client.preferences.AppPreferences
import com.nextcloud.utils.mdm.MDMConfig
import com.owncloud.android.R
import com.owncloud.android.authentication.AuthenticatorActivity
import com.owncloud.android.databinding.ActivitySplashBinding
import com.owncloud.android.ui.activity.BaseActivity
import com.owncloud.android.ui.activity.SettingsActivity
import com.souvera.workspace.mail.ui.MailActivity
import com.souvera.workspace.push.PushTokenFetcher
import javax.inject.Inject

class LauncherActivity : BaseActivity() {

    private lateinit var binding: ActivitySplashBinding

    @Inject
    lateinit var appPreferences: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        // Mandatory to call this before super method to show system launch screen for api level 31+
        installSplashScreen()

        super.onCreate(savedInstanceState)

        binding = ActivitySplashBinding.inflate(layoutInflater)

        setContentView(binding.root)
        updateTitleVisibility()
        scheduleSplashScreen()
    }

    @VisibleForTesting
    fun setSplashTitles(boldText: String, normalText: String) {
        binding.splashScreenBold.visibility = View.VISIBLE
        binding.splashScreenNormal.visibility = View.VISIBLE

        binding.splashScreenBold.text = boldText
        binding.splashScreenNormal.text = normalText
    }

    private fun updateTitleVisibility() {
        if (TextUtils.isEmpty(resources.getString(R.string.splashScreenBold))) {
            binding.splashScreenBold.visibility = View.GONE
        }
        if (TextUtils.isEmpty(resources.getString(R.string.splashScreenNormal))) {
            binding.splashScreenNormal.visibility = View.GONE
        }
    }

    private fun scheduleSplashScreen() {
        Handler(Looper.getMainLooper()).postDelayed({
            // Register FCM token for mail push notifications as early as possible after login,
            // so the server knows about this device even if the user never opens the Mail screen.
            PushTokenFetcher.fetchAndRegister(this) { }

            // Start IMAP IDLE foreground service for instant mail push (independent of FCM)

            if (user.isPresent) {
                if (MDMConfig.enforceProtection(this) && appPreferences.lockPreference == SettingsActivity.LOCK_NONE) {
                    startActivity(Intent(this, SettingsActivity::class.java))
                } else {
                    // Souvera: Mail is the app's main function, so cold start lands on the
                    // Mail home (a drawer-hosted screen); Files/Calendar/Notes stay reachable
                    // via its navigation drawer.
                    startActivity(Intent(this, MailActivity::class.java))
                }
            } else {
                startActivity(Intent(this, AuthenticatorActivity::class.java))
            }
            finish()
        }, SPLASH_DURATION)
    }

    companion object {
        const val SPLASH_DURATION = 1500L
    }
}
