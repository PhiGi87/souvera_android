/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.contacts

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone

/**
 * Reads contacts from the Android Contacts Provider, which the in-app CardDAV sync
 * ([com.souvera.workspace.dav.CardDavSync]) keeps in sync with the server.
 */
@SuppressLint("Recycle")
class ContactsRepository(context: Context) {

    private val resolver = context.contentResolver

    fun loadContacts(): List<ContactSummary> {
        val contacts = mutableListOf<ContactSummary>()
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} IS NOT NULL",
            null,
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1).orEmpty()
                if (name.isNotBlank()) contacts += ContactSummary(cursor.getLong(0), name)
            }
        }
        return contacts
    }

    fun loadDetail(contactId: Long): ContactDetail = ContactDetail(
        emails = queryValues(Email.CONTENT_URI, Email.ADDRESS, contactId),
        phones = queryValues(Phone.CONTENT_URI, Phone.NUMBER, contactId)
    )

    private fun queryValues(uri: Uri, column: String, contactId: Long): List<String> {
        val values = LinkedHashSet<String>()
        resolver.query(
            uri,
            arrayOf(column),
            "${ContactsContract.Data.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                cursor.getString(0)?.takeIf { it.isNotBlank() }?.let { values += it }
            }
        }
        return values.toList()
    }
}
