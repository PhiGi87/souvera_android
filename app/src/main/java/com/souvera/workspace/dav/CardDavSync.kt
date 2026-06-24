/*
 * Souvera Workspace - Android Client
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * CardDAV -> Android Contacts Provider one-way synchronisation.
 */
package com.souvera.workspace.dav

import android.accounts.Account
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import android.util.Log
import ezvcard.Ezvcard

@android.annotation.SuppressLint("MissingPermission")
class CardDavSync(
    private val context: Context,
    private val account: Account,
    private val client: DavClient
) {
    private val resolver = context.contentResolver

    fun sync(homeUrl: String) {
        val addressBooks = client.list(homeUrl).filter { it.isCollection }
        Log.d(TAG, "Found ${addressBooks.size} address books")

        // full refresh: remove previously synced contacts for this account
        resolver.delete(syncUri(RawContacts.CONTENT_URI), null, null)

        addressBooks.forEach { book ->
            runCatching { syncBook(book) }
                .onFailure { Log.e(TAG, "Contacts sync failed for ${book.href}", it) }
        }
    }

    private fun syncBook(book: DavResource) {
        val objects = client.list(book.href)
            .filter { !it.isCollection && it.href.endsWith(".vcf", ignoreCase = true) }

        objects.forEach { obj ->
            val vcf = client.get(obj.href) ?: return@forEach
            runCatching { importVcf(vcf, obj.href) }
                .onFailure { Log.w(TAG, "Skipping unparsable contact ${obj.href}", it) }
        }
        Log.d(TAG, "Imported ${objects.size} contacts from ${book.href}")
    }

    private fun importVcf(vcf: String, sourceId: String) {
        val vcards = Ezvcard.parse(vcf).all()
        vcards.forEach { vcard ->
            val ops = ArrayList<ContentProviderOperation>()
            val rawIndex = 0

            ops.add(
                ContentProviderOperation.newInsert(syncUri(RawContacts.CONTENT_URI))
                    .withValue(RawContacts.ACCOUNT_NAME, account.name)
                    .withValue(RawContacts.ACCOUNT_TYPE, account.type)
                    .withValue(RawContacts.SOURCE_ID, sourceId)
                    .build()
            )

            val displayName = vcard.formattedName?.value
            if (!displayName.isNullOrBlank()) {
                ops.add(
                    dataInsert(rawIndex)
                        .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                        .withValue(StructuredName.DISPLAY_NAME, displayName)
                        .build()
                )
            }

            vcard.telephoneNumbers.forEach { tel ->
                val number = tel.text ?: tel.uri?.number
                if (!number.isNullOrBlank()) {
                    ops.add(
                        dataInsert(rawIndex)
                            .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                            .withValue(Phone.NUMBER, number)
                            .withValue(Phone.TYPE, Phone.TYPE_OTHER)
                            .build()
                    )
                }
            }

            vcard.emails.forEach { email ->
                if (!email.value.isNullOrBlank()) {
                    ops.add(
                        dataInsert(rawIndex)
                            .withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
                            .withValue(Email.ADDRESS, email.value)
                            .withValue(Email.TYPE, Email.TYPE_OTHER)
                            .build()
                    )
                }
            }

            if (ops.size > 1) {
                resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            }
        }
    }

    private fun dataInsert(rawContactBackReference: Int) =
        ContentProviderOperation.newInsert(syncUri(Data.CONTENT_URI))
            .withValueBackReference(Data.RAW_CONTACT_ID, rawContactBackReference)

    private fun syncUri(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
        .appendQueryParameter(RawContacts.ACCOUNT_TYPE, account.type)
        .build()

    @Suppress("unused")
    private fun contactUri(id: Long): Uri = ContentUris.withAppendedId(RawContacts.CONTENT_URI, id)

    companion object {
        const val TAG = "CardDavSync"
    }
}
