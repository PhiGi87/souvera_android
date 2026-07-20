/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Parses iCalendar (ICS) fetched over CalDAV into Android Calendar Provider values.
 */
package com.souvera.workspace.dav

import android.content.ContentValues
import android.provider.CalendarContract.Events
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.DateTime
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VEvent
import java.io.StringReader
import java.util.TimeZone
import java.util.UUID

object CalDavIcsReader {

    fun firstEvent(ics: String): VEvent? {
        val calendar = CalendarBuilder().build(StringReader(ics))
        val events = calendar.getComponents<VEvent>(VEvent.VEVENT)
        return events.firstOrNull { it.getProperty<Property>(Property.RECURRENCE_ID) == null }
    }

    fun toValues(
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

        for (alarm in event.alarms) {
            val trigger = alarm.getProperty<Property>(Property.TRIGGER)?.value ?: continue
            parseTriggerMinutes(trigger)?.let { reminders.add(it) }
        }
        if (reminders.isNotEmpty()) values.put(Events.HAS_ALARM, 1)
        return values
    }

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

    private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    private const val HOUR_MILLIS = 60 * 60 * 1000L
}
