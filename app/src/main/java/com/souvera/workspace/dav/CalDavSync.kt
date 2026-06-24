/*
 * Souvera Workspace - Android Client
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * CalDAV -> Android Calendar Provider one-way synchronisation.
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
import android.util.Log
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.component.VEvent
import java.io.StringReader
import java.util.TimeZone

@android.annotation.SuppressLint("MissingPermission", "Recycle")
class CalDavSync(
    private val context: Context,
    private val account: Account,
    private val client: DavClient
) {
    private val resolver = context.contentResolver

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
        // full refresh: drop existing events then re-import
        resolver.delete(
            syncUri(Events.CONTENT_URI),
            "${Events.CALENDAR_ID} = ?",
            arrayOf(calendarId.toString())
        )

        val objects = client.list(collection.href)
            .filter { !it.isCollection && it.href.endsWith(".ics", ignoreCase = true) }

        objects.forEach { obj ->
            val ics = client.get(obj.href) ?: return@forEach
            runCatching { importIcs(ics, calendarId) }
                .onFailure { Log.w(TAG, "Skipping unparsable event ${obj.href}", it) }
        }
        Log.d(TAG, "Imported ${objects.size} objects into calendar $calendarId")
    }

    private fun importIcs(ics: String, calendarId: Long) {
        val calendar = CalendarBuilder().build(StringReader(ics))
        val events = calendar.getComponents<VEvent>(VEvent.VEVENT)
        for (event in events) {
            val start = event.startDate?.date?.time ?: continue
            val end = event.getEndDate()?.date?.time ?: (start + 60 * 60 * 1000L)
            val allDay = event.startDate?.date !is net.fortuna.ical4j.model.DateTime

            val values = ContentValues().apply {
                put(Events.CALENDAR_ID, calendarId)
                put(Events.DTSTART, start)
                put(Events.DTEND, end)
                put(Events.TITLE, event.summary?.value ?: "(no title)")
                event.description?.value?.let { put(Events.DESCRIPTION, it) }
                event.location?.value?.let { put(Events.EVENT_LOCATION, it) }
                event.uid?.value?.let { put(Events._SYNC_ID, it) }
                put(Events.ALL_DAY, if (allDay) 1 else 0)
                put(Events.EVENT_TIMEZONE, if (allDay) "UTC" else TimeZone.getDefault().id)
            }
            resolver.insert(syncUri(Events.CONTENT_URI), values)
        }
    }

    private fun ensureCalendar(collection: DavResource): Long {
        val existing = findCalendar(collection.href)
        if (existing != null) return existing

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
        val uri: Uri? = resolver.insert(syncUri(Calendars.CONTENT_URI), values)
        return uri?.let { ContentUris.parseId(it) } ?: -1L
    }

    private fun findCalendar(syncId: String): Long? {
        val cursor = resolver.query(
            syncUri(Calendars.CONTENT_URI),
            arrayOf(Calendars._ID),
            "${Calendars._SYNC_ID} = ? AND ${Calendars.ACCOUNT_NAME} = ?",
            arrayOf(syncId, account.name),
            null
        )
        cursor?.use { if (it.moveToFirst()) return it.getLong(0) }
        return null
    }

    private fun syncUri(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, account.name)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type)
        .build()

    companion object {
        const val TAG = "CalDavSync"
        const val DEFAULT_COLOR = 0xFF2F80ED.toInt() // Souvera blue
    }
}
