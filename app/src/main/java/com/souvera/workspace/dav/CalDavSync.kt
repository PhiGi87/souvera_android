/*
 * Souvera Workspace - Android Client
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
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.DateTime
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VEvent
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

@android.annotation.SuppressLint("MissingPermission", "Recycle")
class CalDavSync(
    private val context: Context,
    private val account: Account,
    private val client: DavClient
) {
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("souvera_dav_ctags", Context.MODE_PRIVATE)
    private val utcFmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val dateFmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

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

    // ---- push (device -> server) -----------------------------------------

    private fun pushLocalChanges(calendarId: Long, collectionHref: String) {
        // deletions
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

        // additions & modifications
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

                val ics = buildIcs(cursor, id, uid!!)
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

    // ---- pull (server -> device) -----------------------------------------

    private fun pullRemoteChanges(calendarId: Long, collection: DavResource) {
        val remoteCTag = client.cTag(collection.href)
        val storedCTag = prefs.getString(collection.href, null)

        val local = HashMap<String, Pair<Long, String?>>() // href -> (id, etag)
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

        // delete locally what no longer exists on the server (only clean rows)
        (local.keys - remoteHrefs).forEach { href ->
            local[href]?.first?.let { id ->
                resolver.delete(syncUri(ContentUris.withAppendedId(Events.CONTENT_URI, id)), null, null)
            }
        }

        if (remoteCTag != null) prefs.edit().putString(collection.href, remoteCTag).apply()
    }

    private fun upsertEvent(calendarId: Long, href: String, etag: String?, ics: String, existingId: Long?) {
        val calendar = CalendarBuilder().build(StringReader(ics))
        val events = calendar.getComponents<VEvent>(VEvent.VEVENT)
        val event = events.firstOrNull { it.getProperty<Property>(Property.RECURRENCE_ID) == null } ?: return

        if (existingId != null) {
            resolver.delete(Reminders.CONTENT_URI, "${Reminders.EVENT_ID} = ?", arrayOf(existingId.toString()))
            resolver.delete(syncUri(ContentUris.withAppendedId(Events.CONTENT_URI, existingId)), null, null)
        }

        val reminders = ArrayList<Int>()
        val values = convertToValues(event, calendarId, href, etag, reminders) ?: return
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

    private fun convertToValues(
        event: VEvent,
        calendarId: Long,
        href: String,
        etag: String?,
        reminders: MutableList<Int>
    ): ContentValues? {
        val startDt = event.startDate ?: return null
        val start = startDt.date.time
        val allDay = startDt.date !is DateTime
        val rrule = prop(event, Property.RRULE)
        val rdate = prop(event, Property.RDATE)
        val recurring = rrule != null || rdate != null
        val endMillis = event.getEndDate()?.date?.time

        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId)
            put(Events.DTSTART, start)
            put(Events.TITLE, prop(event, Property.SUMMARY) ?: "(no title)")
            prop(event, Property.DESCRIPTION)?.let { put(Events.DESCRIPTION, it) }
            prop(event, Property.LOCATION)?.let { put(Events.EVENT_LOCATION, it) }
            put(Events._SYNC_ID, href)
            etag?.let { put(Events.SYNC_DATA1, it) }
            put(Events.UID_2445, prop(event, Property.UID) ?: UUID.randomUUID().toString())
            put(Events.DIRTY, 0)
            put(Events.ALL_DAY, if (allDay) 1 else 0)
            put(Events.EVENT_TIMEZONE, if (allDay) "UTC" else TimeZone.getDefault().id)

            if (recurring) {
                rrule?.let { put(Events.RRULE, it) }
                rdate?.let { put(Events.RDATE, it) }
                prop(event, Property.EXDATE)?.let { put(Events.EXDATE, it) }
                put(Events.DURATION, prop(event, Property.DURATION) ?: computeDuration(start, endMillis, allDay))
            } else {
                put(Events.DTEND, endMillis ?: (start + if (allDay) DAY_MILLIS else HOUR_MILLIS))
            }
        }

        // VALARM -> reminders
        for (alarm in event.alarms) {
            val trigger = alarm.getProperty<Property>(Property.TRIGGER)?.value ?: continue
            parseTriggerMinutes(trigger)?.let { reminders.add(it) }
        }
        if (reminders.isNotEmpty()) values.put(Events.HAS_ALARM, 1)
        return values
    }

    // ---- ICS export -------------------------------------------------------

    private fun buildIcs(cursor: android.database.Cursor, eventId: Long, uid: String): String {
        fun col(name: String): String? {
            val idx = cursor.getColumnIndex(name)
            return if (idx >= 0 && !cursor.isNull(idx)) cursor.getString(idx) else null
        }

        val allDay = col(Events.ALL_DAY) == "1"
        val dtStart = col(Events.DTSTART)?.toLongOrNull() ?: System.currentTimeMillis()
        val dtEnd = col(Events.DTEND)?.toLongOrNull()
        val duration = col(Events.DURATION)
        val rrule = col(Events.RRULE)
        val sb = StringBuilder()
        sb.append("BEGIN:VCALENDAR\r\n")
        sb.append("VERSION:2.0\r\n")
        sb.append("PRODID:-//Souvera Workspace//Android//EN\r\n")
        sb.append("BEGIN:VEVENT\r\n")
        sb.append("UID:").append(uid).append("\r\n")
        sb.append("DTSTAMP:").append(utcFmt.format(Date())).append("\r\n")
        if (allDay) {
            sb.append("DTSTART;VALUE=DATE:").append(dateFmt.format(Date(dtStart))).append("\r\n")
            if (dtEnd != null) sb.append("DTEND;VALUE=DATE:").append(dateFmt.format(Date(dtEnd))).append("\r\n")
        } else {
            sb.append("DTSTART:").append(utcFmt.format(Date(dtStart))).append("\r\n")
            when {
                !duration.isNullOrBlank() -> sb.append("DURATION:").append(duration).append("\r\n")
                dtEnd != null -> sb.append("DTEND:").append(utcFmt.format(Date(dtEnd))).append("\r\n")
            }
        }
        col(Events.TITLE)?.let { sb.append("SUMMARY:").append(escape(it)).append("\r\n") }
        col(Events.DESCRIPTION)?.let { sb.append("DESCRIPTION:").append(escape(it)).append("\r\n") }
        col(Events.EVENT_LOCATION)?.let { sb.append("LOCATION:").append(escape(it)).append("\r\n") }
        if (!rrule.isNullOrBlank()) sb.append("RRULE:").append(rrule).append("\r\n")

        // reminders
        resolver.query(
            Reminders.CONTENT_URI, arrayOf(Reminders.MINUTES),
            "${Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()), null
        )?.use { rc ->
            while (rc.moveToNext()) {
                val minutes = rc.getInt(0)
                sb.append("BEGIN:VALARM\r\n")
                sb.append("ACTION:DISPLAY\r\n")
                sb.append("DESCRIPTION:Reminder\r\n")
                sb.append("TRIGGER:-PT").append(minutes).append("M\r\n")
                sb.append("END:VALARM\r\n")
            }
        }
        sb.append("END:VEVENT\r\n")
        sb.append("END:VCALENDAR\r\n")
        return sb.toString()
    }

    // ---- calendar collection ---------------------------------------------

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

    // ---- helpers ----------------------------------------------------------

    private fun prop(e: VEvent, name: String): String? = e.getProperty<Property>(name)?.value

    private fun computeDuration(start: Long, end: Long?, allDay: Boolean): String {
        if (end == null) return if (allDay) "P1D" else "PT1H"
        val diff = (end - start).coerceAtLeast(0)
        return if (allDay) "P${(diff / DAY_MILLIS).coerceAtLeast(1)}D" else "PT${diff / 1000}S"
    }

    private fun parseTriggerMinutes(value: String): Int? {
        val body = value.trimStart('-', '+')
        val regex = Regex("P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?")
        val m = regex.matchEntire(body) ?: return null
        val days = m.groupValues[1].toIntOrNull() ?: 0
        val hours = m.groupValues[2].toIntOrNull() ?: 0
        val mins = m.groupValues[3].toIntOrNull() ?: 0
        return days * 1440 + hours * 60 + mins
    }

    private fun escape(text: String): String = text
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace(";", "\\;")
        .replace(",", "\\,")

    private fun syncUri(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, account.name)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type)
        .build()

    companion object {
        const val TAG = "CalDavSync"
        const val DEFAULT_COLOR = 0xFF2F80ED.toInt()
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        private const val HOUR_MILLIS = 60 * 60 * 1000L
        private val EXPORT_COLUMNS = arrayOf(
            Events._ID, Events._SYNC_ID, Events.UID_2445, Events.SYNC_DATA1,
            Events.DTSTART, Events.DTEND, Events.DURATION, Events.ALL_DAY,
            Events.EVENT_TIMEZONE, Events.TITLE, Events.DESCRIPTION,
            Events.EVENT_LOCATION, Events.RRULE
        )
    }
}
