/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Serialises Android Calendar Provider rows into iCalendar (ICS) for CalDAV upload.
 */
package com.souvera.workspace.dav

import android.content.ContentResolver
import android.database.Cursor
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@android.annotation.SuppressLint("MissingPermission", "Recycle")
class CalDavIcsWriter(private val resolver: ContentResolver) {
    private val utcFmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val dateFmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun build(cursor: Cursor, eventId: Long, uid: String): String {
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

    private fun escape(text: String): String = text
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace(";", "\\;")
        .replace(",", "\\,")
}
