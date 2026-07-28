/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.dav

import android.content.Context

/**
 * Which server calendars/address books the in-app sync should include. The per-collection toggle
 * values live in the default shared preferences under [KEY_PREFIX]+href so the checkboxes in the
 * global settings screen (SettingsActivity, built from [knownCalendars]/[knownAddressBooks])
 * persist them directly; the sync stores the discovered collections here on every run.
 */
class DavCollectionSettings(context: Context) {

    private val prefs =
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

    fun isEnabled(href: String): Boolean = prefs.getBoolean(KEY_PREFIX + href, true)

    fun storeCalendars(collections: List<DavCollectionInfo>) = store(KEY_KNOWN_CALENDARS, collections)

    fun storeAddressBooks(collections: List<DavCollectionInfo>) = store(KEY_KNOWN_ADDRESS_BOOKS, collections)

    fun knownCalendars(): List<DavCollectionInfo> = known(KEY_KNOWN_CALENDARS)

    fun knownAddressBooks(): List<DavCollectionInfo> = known(KEY_KNOWN_ADDRESS_BOOKS)

    private fun store(key: String, collections: List<DavCollectionInfo>) {
        val encoded = collections.map { "${it.href}$SEPARATOR${it.displayName}" }.toSet()
        prefs.edit().putStringSet(key, encoded).apply()
    }

    private fun known(key: String): List<DavCollectionInfo> = prefs.getStringSet(key, emptySet()).orEmpty()
        .map { entry ->
            val separatorIndex = entry.indexOf(SEPARATOR)
            if (separatorIndex < 0) {
                DavCollectionInfo(entry, "")
            } else {
                DavCollectionInfo(entry.substring(0, separatorIndex), entry.substring(separatorIndex + 1))
            }
        }
        .sortedBy { it.displayName.lowercase() }

    companion object {
        const val KEY_PREFIX = "dav_sync_"
        private const val KEY_KNOWN_CALENDARS = "dav_known_calendars"
        private const val KEY_KNOWN_ADDRESS_BOOKS = "dav_known_addressbooks"
        private const val SEPARATOR_CODE = 31
        private val SEPARATOR = SEPARATOR_CODE.toChar()
    }
}
