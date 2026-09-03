/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.calendar

import android.accounts.Account
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import java.util.TimeZone

/**
 * Reads and writes events through the Android Calendar Provider. Writes are made as a normal app
 * (not as a sync adapter), so the provider marks them DIRTY/DELETED and the in-app CalDAV sync
 * ([com.souvera.workspace.dav.CalDavSync]) pushes them to the server on the next run.
 */
@SuppressLint("MissingPermission", "Recycle")
class CalendarRepository(context: Context) {

    private val resolver = context.contentResolver

    @Suppress("MagicNumber")
    fun loadDay(begin: Long, end: Long): List<CalendarEvent> {
        val projection = arrayOf(
            Instances.EVENT_ID,
            Instances.TITLE,
            Instances.BEGIN,
            Instances.END,
            Instances.EVENT_LOCATION,
            Instances.ALL_DAY,
            Instances.CALENDAR_ID
        )
        val events = mutableListOf<CalendarEvent>()
        Instances.query(resolver, projection, begin, end)?.use { cursor ->
            while (cursor.moveToNext()) {
                events += CalendarEvent(
                    id = cursor.getLong(0),
                    title = cursor.getString(1).orEmpty(),
                    begin = cursor.getLong(2),
                    end = cursor.getLong(3),
                    location = cursor.getString(4),
                    allDay = cursor.getInt(5) == 1,
                    calendarId = cursor.getLong(6)
                )
            }
        }
        return events.sortedBy { it.begin }
    }

    /** Alle im Provider vorhandenen Kalender (für die Kalender-Auswahl). */
    fun listCalendars(): List<CalendarInfo> {
        val projection = arrayOf(
            Calendars._ID,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.ACCOUNT_NAME,
            Calendars.CALENDAR_COLOR
        )
        val result = mutableListOf<CalendarInfo>()
        resolver.query(
            Calendars.CONTENT_URI,
            projection,
            null,
            null,
            "${Calendars.CALENDAR_DISPLAY_NAME} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                result += CalendarInfo(
                    id = cursor.getLong(0),
                    displayName = cursor.getString(1).orEmpty(),
                    accountName = cursor.getString(2).orEmpty(),
                    color = cursor.getInt(3)
                )
            }
        }
        return result
    }

    @Suppress("MagicNumber")
    fun loadEvent(id: Long): EventDraft? {
        val projection = arrayOf(
            Events.TITLE,
            Events.EVENT_LOCATION,
            Events.DTSTART,
            Events.DTEND,
            Events.ALL_DAY,
            Events.DESCRIPTION
        )
        resolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, id), projection, null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return EventDraft(
                        id = id,
                        title = cursor.getString(0).orEmpty(),
                        location = cursor.getString(1).orEmpty(),
                        begin = cursor.getLong(2),
                        end = cursor.getLong(3),
                        allDay = cursor.getInt(4) == 1,
                        description = cursor.getString(5)
                    )
                }
            }
        return null
    }

    fun save(account: Account, draft: EventDraft): Boolean {
        val values = ContentValues().apply {
            put(Events.TITLE, draft.title)
            put(Events.EVENT_LOCATION, draft.location)
            put(Events.DTSTART, draft.begin)
            put(Events.DTEND, draft.end)
            put(Events.ALL_DAY, if (draft.allDay) 1 else 0)
            put(Events.EVENT_TIMEZONE, if (draft.allDay) TIMEZONE_UTC else TimeZone.getDefault().id)
            draft.description?.let { put(Events.DESCRIPTION, it) }
        }
        return if (draft.id != null) {
            resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, draft.id), values, null, null) > 0
        } else {
            val calendarId = writableCalendarId(account) ?: return false
            values.put(Events.CALENDAR_ID, calendarId)
            resolver.insert(Events.CONTENT_URI, values) != null
        }
    }

    fun delete(id: Long): Boolean = resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null) > 0

    private fun writableCalendarId(account: Account): Long? {
        resolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID),
            "${Calendars.ACCOUNT_TYPE} = ? AND ${Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(account.type, Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            "${Calendars._ID} ASC"
        )?.use { cursor -> if (cursor.moveToFirst()) return cursor.getLong(0) }
        return null
    }

    companion object {
        private const val TIMEZONE_UTC = "UTC"
    }
}
