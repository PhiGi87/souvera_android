/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail

import android.content.Context

/**
 * Read access to the mail preferences. The values are edited in the app's global settings screen
 * ([com.owncloud.android.ui.activity.SettingsActivity], "Mail" category in res/xml/preferences.xml)
 * and therefore live in the default shared preferences, with the message limit stored as a string
 * the way ListPreference persists it.
 */
class MailSettings(context: Context) {

    private val prefs =
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

    val messageLimit: Int
        get() = prefs.getString(KEY_MESSAGE_LIMIT, null)?.toIntOrNull() ?: DEFAULT_MESSAGE_LIMIT

    val loadRemoteImages: Boolean
        get() = prefs.getBoolean(KEY_LOAD_REMOTE_IMAGES, false)

    val signature: String
        get() = prefs.getString(KEY_SIGNATURE, "").orEmpty()

    companion object {
        private const val DEFAULT_MESSAGE_LIMIT = 50
        private const val KEY_MESSAGE_LIMIT = "mail_message_limit"
        private const val KEY_LOAD_REMOTE_IMAGES = "mail_load_remote_images"
        private const val KEY_SIGNATURE = "mail_signature"
    }
}
