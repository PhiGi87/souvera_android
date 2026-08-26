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
        if (query.isBlank()) return emptyList()

        val results = LinkedHashMap<String, RecipientSuggestion>()
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
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
        }

        // Server-Fallback: Kontakte aus dem Souvera-Adressbuch, falls das
        // Geräte-Adressbuch (noch) leer ist.
        if (results.size < limit) {
            for (suggestion in searchServer(query, limit - results.size)) {
                val key = suggestion.email.lowercase()
                if (suggestion.email.isNotBlank() && !results.containsKey(key)) {
                    results[key] = suggestion
                }
            }
        }

        return results.values.toList()
    }

    /** Sucht zusätzlich im Server-Adressbuch (souvera_mail Contacts-API). */
    private fun searchServer(query: String, limit: Int): List<RecipientSuggestion> = runCatching {
        val account = android.accounts.AccountManager.get(context)
            .getAccountsByType(context.getString(com.owncloud.android.R.string.account_type))
            .firstOrNull() ?: return emptyList()
        val dav = com.souvera.workspace.dav.SouveraSyncManager(context).resolve(account) ?: return emptyList()
        val base = dav.baseUrl.trimEnd('/')
        val url = "$base/apps/souvera_mail/api/v2/contacts/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=$limit"
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("Authorization", okhttp3.Credentials.basic(dav.username, dav.password))
            .header("Accept", "application/json")
            .build()
        val body = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
            .newCall(request).execute().use { it.body?.string() } ?: return emptyList()
        val root = org.json.JSONObject(body)
        val contacts = root.optJSONArray("contacts") ?: return emptyList()
        val out = mutableListOf<RecipientSuggestion>()
        for (i in 0 until contacts.length()) {
            val c = contacts.getJSONObject(i)
            val email = c.optString("email").orEmpty()
            val name = c.optString("name").orEmpty()
            if (email.isNotBlank()) {
                out += RecipientSuggestion(name.takeIf { it.isNotBlank() && it != email }, email)
            }
        }
        out
    }.getOrDefault(emptyList())
}
