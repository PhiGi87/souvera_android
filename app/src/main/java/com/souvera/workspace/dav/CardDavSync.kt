/*
 * Souvera Workspace - Android Client
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Two-way CardDAV synchronisation between a Nextcloud server and the Android
 * Contacts Provider, using CTag/ETag for incremental sync.
 */
package com.souvera.workspace.dav

import android.accounts.Account
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
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
import ezvcard.VCard
import ezvcard.VCardVersion
import ezvcard.property.Uid
import java.util.UUID

@android.annotation.SuppressLint("MissingPermission", "Recycle")
class CardDavSync(
    private val context: Context,
    private val account: Account,
    private val client: DavClient
) {
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("souvera_dav_ctags", Context.MODE_PRIVATE)

    fun sync(homeUrl: String) {
        val addressBooks = client.list(homeUrl).filter { it.isCollection }
        Log.d(TAG, "Found ${addressBooks.size} address books")
        addressBooks.forEach { book ->
            runCatching { syncBook(book) }
                .onFailure { Log.e(TAG, "Contacts sync failed for ${book.href}", it) }
        }
    }

    private fun syncBook(book: DavResource) {
        pushLocalChanges(book.href)
        pullRemoteChanges(book)
    }

    // ---- push (device -> server) -----------------------------------------

    private fun pushLocalChanges(collectionHref: String) {
        // deletions
        resolver.query(
            syncUri(RawContacts.CONTENT_URI),
            arrayOf(RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.SYNC1),
            "${RawContacts.DELETED} = 1", null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val href = cursor.getString(1)
                val etag = cursor.getString(2)
                if (!href.isNullOrBlank()) client.delete(href, etag)
                resolver.delete(syncUri(ContentUris.withAppendedId(RawContacts.CONTENT_URI, id)), null, null)
            }
        }

        // additions & modifications
        resolver.query(
            syncUri(RawContacts.CONTENT_URI),
            arrayOf(RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.SYNC1, RawContacts.SYNC2),
            "${RawContacts.DIRTY} = 1 AND ${RawContacts.DELETED} = 0", null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                var href = cursor.getString(1)
                val etag = cursor.getString(2)
                var uid = cursor.getString(3)
                val isNew = href.isNullOrBlank()
                if (uid.isNullOrBlank()) uid = UUID.randomUUID().toString()
                if (isNew) href = "$collectionHref$uid.vcf"

                val vcf = buildVCard(id, uid!!)
                val result = client.put(
                    href!!, vcf, "text/vcard; charset=utf-8",
                    ifMatch = if (isNew) null else etag,
                    ifNoneMatch = isNew
                )
                if (result.success) {
                    val values = ContentValues().apply {
                        put(RawContacts.SOURCE_ID, href)
                        put(RawContacts.SYNC2, uid)
                        put(RawContacts.DIRTY, 0)
                        result.etag?.let { put(RawContacts.SYNC1, it) }
                    }
                    resolver.update(
                        syncUri(ContentUris.withAppendedId(RawContacts.CONTENT_URI, id)),
                        values, null, null
                    )
                }
            }
        }
    }

    // ---- pull (server -> device) -----------------------------------------

    private fun pullRemoteChanges(book: DavResource) {
        val remoteCTag = client.cTag(book.href)
        val storedCTag = prefs.getString(book.href, null)

        val local = HashMap<String, Pair<Long, String?>>() // href -> (id, etag)
        resolver.query(
            syncUri(RawContacts.CONTENT_URI),
            arrayOf(RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.SYNC1, RawContacts.DIRTY),
            "${RawContacts.SOURCE_ID} IS NOT NULL", null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val dirty = cursor.getInt(3) == 1
                val href = cursor.getString(1) ?: continue
                if (!dirty) local[href] = cursor.getLong(0) to cursor.getString(2)
            }
        }

        if (remoteCTag != null && remoteCTag == storedCTag) {
            Log.d(TAG, "CTag unchanged for ${book.href}, skipping pull")
            return
        }

        val objects = client.list(book.href)
            .filter { !it.isCollection && it.href.endsWith(".vcf", ignoreCase = true) }
        val remoteHrefs = HashSet<String>()

        objects.forEach { obj ->
            remoteHrefs.add(obj.href)
            val localEntry = local[obj.href]
            if (localEntry == null || localEntry.second == null || localEntry.second != obj.etag) {
                val vcf = client.get(obj.href) ?: return@forEach
                runCatching { upsertContact(obj.href, obj.etag, vcf, localEntry?.first) }
                    .onFailure { Log.w(TAG, "Skipping unparsable contact ${obj.href}", it) }
            }
        }

        (local.keys - remoteHrefs).forEach { href ->
            local[href]?.first?.let { id ->
                resolver.delete(syncUri(ContentUris.withAppendedId(RawContacts.CONTENT_URI, id)), null, null)
            }
        }

        if (remoteCTag != null) prefs.edit().putString(book.href, remoteCTag).apply()
    }

    private fun upsertContact(href: String, etag: String?, vcf: String, existingId: Long?) {
        val vcard = Ezvcard.parse(vcf).first() ?: return
        if (existingId != null) {
            resolver.delete(syncUri(ContentUris.withAppendedId(RawContacts.CONTENT_URI, existingId)), null, null)
        }

        val ops = ArrayList<ContentProviderOperation>()
        ops.add(
            ContentProviderOperation.newInsert(syncUri(RawContacts.CONTENT_URI))
                .withValue(RawContacts.ACCOUNT_NAME, account.name)
                .withValue(RawContacts.ACCOUNT_TYPE, account.type)
                .withValue(RawContacts.SOURCE_ID, href)
                .withValue(RawContacts.SYNC1, etag)
                .withValue(RawContacts.SYNC2, vcard.uid?.value)
                .withValue(RawContacts.DIRTY, 0)
                .build()
        )

        vcard.formattedName?.value?.takeIf { it.isNotBlank() }?.let { name ->
            ops.add(
                dataInsert()
                    .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(StructuredName.DISPLAY_NAME, name)
                    .build()
            )
        }
        vcard.telephoneNumbers.forEach { tel ->
            val number = tel.text ?: tel.uri?.number
            if (!number.isNullOrBlank()) {
                ops.add(
                    dataInsert()
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
                    dataInsert()
                        .withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
                        .withValue(Email.ADDRESS, email.value)
                        .withValue(Email.TYPE, Email.TYPE_OTHER)
                        .build()
                )
            }
        }
        resolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }

    // ---- vCard export -----------------------------------------------------

    private fun buildVCard(rawContactId: Long, uid: String): String {
        val vcard = VCard()
        vcard.uid = Uid(uid)
        resolver.query(
            Data.CONTENT_URI,
            arrayOf(Data.MIMETYPE, Data.DATA1),
            "${Data.RAW_CONTACT_ID} = ?", arrayOf(rawContactId.toString()), null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val mime = cursor.getString(0)
                val value = cursor.getString(1) ?: continue
                when (mime) {
                    StructuredName.CONTENT_ITEM_TYPE -> if (vcard.formattedName == null) vcard.setFormattedName(value)
                    Phone.CONTENT_ITEM_TYPE -> vcard.addTelephoneNumber(value)
                    Email.CONTENT_ITEM_TYPE -> vcard.addEmail(value)
                }
            }
        }
        if (vcard.formattedName == null) vcard.setFormattedName(uid)
        return Ezvcard.write(vcard).version(VCardVersion.V3_0).go()
    }

    // ---- helpers ----------------------------------------------------------

    private fun dataInsert() =
        ContentProviderOperation.newInsert(syncUri(Data.CONTENT_URI))
            .withValueBackReference(Data.RAW_CONTACT_ID, 0)

    private fun syncUri(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
        .appendQueryParameter(RawContacts.ACCOUNT_TYPE, account.type)
        .build()

    companion object {
        const val TAG = "CardDavSync"
    }
}
