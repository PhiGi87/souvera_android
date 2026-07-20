/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Two-way CalDAV synchronisation between a Nextcloud server and the Android
 * Calendar Provider. Handles recurrence (RRULE/RDATE/EXDATE), reminders
 * (VALARM) and all-day events, using CTag/ETag for incremental sync.
 */
package com.souvera.workspace.dav

import android.accounts.Account
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import android.util.Log
import java.util.UUID

@android.annotation.SuppressLint("MissingPermission", "Recycle")
class CalDavSync(
    private val context: Context,
    private val account: Account,
    private val client: DavClient
) {
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("souvera_dav_ctags", Context.MODE_PRIVATE)
    private val icsWriter = CalDavIcsWriter(resolver)

    fun sync(homeUrl: String) {
        val collections = client.list(homeUrl).filter { it.isCollection }
        Log.d(TAG, "Found ${collections.size} calendar collections")
        collections.forEach { collection ->
            runCatching { syncCollection(collection) }
                .onFailure { Log.e(TAG, "Calendar sync failed for ${collection.href}", it) }
        }
    }

    private fun syncCollection(collection: DavResource) {
        val calendarId = ensureCalendar(collection)
        if (calendarId < 0) return
        pushLocalChanges(calendarId, collection.href)
        pullRemoteChanges(calendarId, collection)
    }

    private fun pushLocalChanges(calendarId: Long, collectionHref: String) {
        resolver.query(
            syncUri(Events.CONTENT_URI),
            arrayOf(Events._ID, Events._SYNC_ID, Events.SYNC_DATA1),
            "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 1",
            arrayOf(calendarId.toString()), null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val href = cursor.getString(1)
                val etag = cursor.getString(2)
                if (!href.isNullOrBlank()) client.delete(href, etag)
                resolver.delete(syncUri(ContentUris.withAppendedId(Events.CONTENT_URI, id)), null, null)
            }
        }

        resolver.query(
            syncUri(Events.CONTENT_URI),
            EXPORT_COLUMNS,
            "${Events.CALENDAR_ID} = ? AND ${Events.DIRTY} = 1 AND ${Events.DELETED} = 0",
            arrayOf(calendarId.toString()), null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(Events._ID))
                var href = cursor.getString(cursor.getColumnIndexOrThrow(Events._SYNC_ID))
                var uid = cursor.getString(cursor.getColumnIndexOrThrow(Events.UID_2445))
                val etag = cursor.getString(cursor.getColumnIndexOrThrow(Events.SYNC_DATA1))
                val isNew = href.isNullOrBlank()
                if (uid.isNullOrBlank()) uid = UUID.randomUUID().toString()
                if (isNew) href = "$collectionHref$uid.ics"

                val ics = icsWriter.build(cursor, id, uid!!)
                val result = client.put(
                    href!!, ics, "text/calendar; charset=utf-8",
                    ifMatch = if (isNew) null else etag,
                    ifNoneMatch = isNew
                )
                if (result.success) {
                    val values = ContentValues().apply {
                        put(Events._SYNC_ID, href)
                        put(Events.UID_2445, uid)
                        put(Events.DIRTY, 0)
                        result.etag?.let { put(Events.SYNC_DATA1, it) }
                    }
                    resolver.update(
                        syncUri(ContentUris.withAppendedId(Events.CONTENT_URI, id)),
                        values, null, null
                    )
                }
            }
        }
    }

    private fun pullRemoteChanges(calendarId: Long, collection: DavResource) {
        val remoteCTag = client.cTag(collection.href)
        val storedCTag = prefs.getString(collection.href, null)

        val local = HashMap<String, Pair<Long, String?>>()
        resolver.query(
            syncUri(Events.CONTENT_URI),
            arrayOf(Events._ID, Events._SYNC_ID, Events.SYNC_DATA1, Events.DIRTY),
            "${Events.CALENDAR_ID} = ? AND ${Events._SYNC_ID} IS NOT NULL",
            arrayOf(calendarId.toString()), null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val dirty = cursor.getInt(3) == 1
                val href = cursor.getString(1) ?: continue
                if (!dirty) local[href] = cursor.getLong(0) to cursor.getString(2)
            }
        }

        if (remoteCTag != null && remoteCTag == storedCTag) {
            Log.d(TAG, "CTag unchanged for ${collection.href}, skipping pull")
            return
        }

        val objects = client.list(collection.href)
            .filter { !it.isCollection && it.href.endsWith(".ics", ignoreCase = true) }
        val remoteHrefs = HashSet<String>()

        objects.forEach { obj ->
            remoteHrefs.add(obj.href)
            val localEntry = local[obj.href]
            if (localEntry == null || localEntry.second == null || localEntry.second != obj.etag) {
                val ics = client.get(obj.href) ?: return@forEach
                runCatching { upsertEvent(calendarId, obj.href, obj.etag, ics, localEntry?.first) }
                    .onFailure { Log.w(TAG, "Skipping unparsable event ${obj.href}", it) }
            }
        }

        (local.keys - remoteHrefs).forEach { href ->
            local[href]?.first?.let { id ->
                resolver.delete(syncUri(ContentUris.withAppendedId(Events.CONTENT_URI, id)), null, null)
            }
        }

        if (remoteCTag != null) prefs.edit().putString(collection.href, remoteCTag).apply()
    }

    private fun upsertEvent(calendarId: Long, href: String, etag: String?, ics: String, existingId: Long?) {
        val event = CalDavIcsReader.firstEvent(ics) ?: return

        if (existingId != null) {
            resolver.delete(Reminders.CONTENT_URI, "${Reminders.EVENT_ID} = ?", arrayOf(existingId.toString()))
            resolver.delete(syncUri(ContentUris.withAppendedId(Events.CONTENT_URI, existingId)), null, null)
        }

        val reminders = ArrayList<Int>()
        val values = CalDavIcsReader.toValues(event, calendarId, href, etag, reminders) ?: return
        val uri = resolver.insert(syncUri(Events.CONTENT_URI), values) ?: return
        val eventId = ContentUris.parseId(uri)
        reminders.forEach { minutes ->
            val r = ContentValues().apply {
                put(Reminders.EVENT_ID, eventId)
                put(Reminders.MINUTES, minutes)
                put(Reminders.METHOD, Reminders.METHOD_ALERT)
            }
            resolver.insert(syncUri(Reminders.CONTENT_URI), r)
        }
    }

    private fun ensureCalendar(collection: DavResource): Long {
        findCalendar(collection.href)?.let { return it }
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, account.name)
            put(Calendars.ACCOUNT_TYPE, account.type)
            put(Calendars.NAME, collection.href)
            put(Calendars.CALENDAR_DISPLAY_NAME, collection.displayName ?: "Souvera Calendar")
            put(Calendars.CALENDAR_COLOR, DEFAULT_COLOR)
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, account.name)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.VISIBLE, 1)
            put(Calendars._SYNC_ID, collection.href)
        }
        val uri = resolver.insert(syncUri(Calendars.CONTENT_URI), values)
        return uri?.let { ContentUris.parseId(it) } ?: -1L
    }

    private fun findCalendar(syncId: String): Long? {
        resolver.query(
            syncUri(Calendars.CONTENT_URI), arrayOf(Calendars._ID),
            "${Calendars._SYNC_ID} = ? AND ${Calendars.ACCOUNT_NAME} = ?",
            arrayOf(syncId, account.name), null
        )?.use { if (it.moveToFirst()) return it.getLong(0) }
        return null
    }

    private fun syncUri(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, account.name)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type)
        .build()

    companion object {
        const val TAG = "CalDavSync"
        const val DEFAULT_COLOR = 0xFF2F80ED.toInt()
        private val EXPORT_COLUMNS = arrayOf(
            Events._ID, Events._SYNC_ID, Events.UID_2445, Events.SYNC_DATA1,
            Events.DTSTART, Events.DTEND, Events.DURATION, Events.ALL_DAY,
            Events.EVENT_TIMEZONE, Events.TITLE, Events.DESCRIPTION,
            Events.EVENT_LOCATION, Events.RRULE
        )
    }
}
