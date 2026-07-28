/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import androidx.core.content.ContextCompat
import com.souvera.workspace.mail.model.RecipientSuggestion

/**
 * Looks up e-mail addresses in the device contacts (which the in-app CardDAV sync keeps filled)
 * for the composer's recipient autocomplete. Without the contacts permission it just returns
 * nothing instead of prompting - the composer works fine without suggestions.
 */
class ContactSuggestionSource(private val context: Context) {

    fun search(query: String, limit: Int): List<RecipientSuggestion> {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted || query.isBlank()) return emptyList()

        val results = LinkedHashMap<String, RecipientSuggestion>()
        val filter = "%$query%"
        context.contentResolver.query(
            Email.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, Email.ADDRESS),
            "${Email.ADDRESS} LIKE ? OR ${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?",
            arrayOf(filter, filter),
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
        )?.use { cursor ->
            while (cursor.moveToNext() && results.size < limit) {
                val name = cursor.getString(0)
                val address = cursor.getString(1).orEmpty()
                val key = address.lowercase()
                if (address.isNotBlank() && !results.containsKey(key)) {
                    results[key] =
                        RecipientSuggestion(name?.takeIf { it.isNotBlank() && it != address }, address)
                }
            }
        }
        return results.values.toList()
    }
}
