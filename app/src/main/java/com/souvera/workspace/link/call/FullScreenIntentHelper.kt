/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.call

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Google-Play-Richtlinie USE_FULL_SCREEN_INTENT: Ab Android 14 muss die App
 * prüfen, ob die Berechtigung gewährt ist, und sie andernfalls beim Nutzer
 * anfordern. Diese Helferklasse fragt einmalig (pro App-Version) nach und
 * öffnet bei Zustimmung die Systemeinstellungen der App.
 */
object FullScreenIntentHelper {

    private const val PREFS = "fsi_request"
    private const val KEY_PROMPTED = "prompted_version"

    fun shouldPrompt(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
            ?: return false
        if (nm.canUseFullScreenIntent()) return false
        val currentVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        }.getOrDefault(0)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_PROMPTED, 0) != currentVersion
    }

    fun markPrompted(context: Context) {
        val currentVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        }.getOrDefault(0)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_PROMPTED, currentVersion).apply()
    }

    /** Öffnet die Systemeinstellung für die Vollbild-Benachrichtigungen der App. */
    fun openSettings(activity: Activity) {
        runCatching {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(intent)
        }
    }
}
