/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.owncloud.android.R
import com.souvera.workspace.ui.SouveraAlertDialog
import com.souvera.workspace.ui.SouveraContentBackground
import com.souvera.workspace.ui.SouveraTopBar
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Umschaltbare Kalender-Ansichten (wie in Outlook). */
enum class CalendarViewMode { DAY, WEEK, MONTH }

private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
private const val MONTH_CELLS = 42

@Composable
fun CalendarScreen(
    repository: CalendarRepository,
    reloadTrigger: Int,
    onOpenEditor: (Long?, Long) -> Unit,
    onBack: () -> Unit,
    onSync: () -> Unit
) {
    var viewMode by remember { mutableStateOf(CalendarViewMode.MONTH) }
    var focusDay by remember { mutableStateOf(startOfToday()) }
    var events by remember { mutableStateOf<List<CalendarEvent>>(emptyList()) }
    var calendars by remember { mutableStateOf<List<CalendarInfo>>(emptyList()) }
    var visibleCalendarIds by remember { mutableStateOf<Set<Long>?>(null) }
    var showCalendarDialog by remember { mutableStateOf(false) }
    var showViewDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current

    LaunchedEffect(Unit, reloadTrigger) {
        if (canReadCalendar(context)) {
            calendars = runCatching { repository.listCalendars() }.getOrDefault(emptyList())
        } else {
            calendars = emptyList()
        }
    }

    LaunchedEffect(viewMode, focusDay, visibleCalendarIds, reloadTrigger) {
        if (!canReadCalendar(context)) {
            events = emptyList()
            return@LaunchedEffect
        }
        val range = rangeFor(viewMode, focusDay)
        val all = runCatching { repository.loadDay(range.first, range.second) }.getOrDefault(emptyList())
        events = visibleCalendarIds?.let { ids -> all.filter { it.calendarId in ids } } ?: all
    }

    Scaffold(
        containerColor = SouveraContentBackground(),
        topBar = {
            SouveraTopBar(
                title = { Text(stringResourceCompat(R.string.drawer_item_calendar)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { showCalendarDialog = true }) {
                        Icon(
                            Icons.Filled.DateRange,
                            contentDescription = stringResourceCompat(R.string.souvera_calendar_pick)
                        )
                    }
                    IconButton(onClick = { showViewDialog = true }) {
                        Icon(
                            Icons.Filled.List,
                            contentDescription = stringResourceCompat(R.string.souvera_calendar_view)
                        )
                    }
                    IconButton(onClick = onSync) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResourceCompat(R.string.souvera_sync_action)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { onOpenEditor(null, focusDay) }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResourceCompat(R.string.souvera_calendar_add))
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (viewMode) {
                CalendarViewMode.MONTH -> MonthView(
                    focusDay = focusDay,
                    events = events,
                    calendars = calendars,
                    onPickDay = { day -> focusDay = day; viewMode = CalendarViewMode.DAY },
                    onPrev = { focusDay = addMonths(focusDay, -1) },
                    onNext = { focusDay = addMonths(focusDay, 1) },
                    onTitle = { focusDay = startOfToday() }
                )
                CalendarViewMode.WEEK -> WeekView(
                    focusDay = focusDay,
                    events = events,
                    calendars = calendars,
                    onPickDay = { day -> focusDay = day; viewMode = CalendarViewMode.DAY },
                    onPrev = { focusDay = addDays(focusDay, -7) },
                    onNext = { focusDay = addDays(focusDay, 7) }
                )
                CalendarViewMode.DAY -> DayView(
                    focusDay = focusDay,
                    events = events,
                    calendars = calendars,
                    onPickEvent = { event -> onOpenEditor(event.id, focusDay) },
                    onPrev = { focusDay = addDays(focusDay, -1) },
                    onNext = { focusDay = addDays(focusDay, 1) }
                )
            }
        }
    }

    if (showViewDialog) {
        SouveraAlertDialog(
            onDismissRequest = { showViewDialog = false },
            title = stringResourceCompat(R.string.souvera_calendar_view),
            dismissText = stringResourceCompat(R.string.common_cancel)
        ) {
            CalendarViewMode.entries.forEach { mode ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewMode = mode; showViewDialog = false }
                        .padding(vertical = 2.dp)
                ) {
                    RadioButton(selected = viewMode == mode, onClick = { viewMode = mode; showViewDialog = false })
                    Text(
                        stringResourceCompat(
                            when (mode) {
                                CalendarViewMode.DAY -> R.string.souvera_calendar_day
                                CalendarViewMode.WEEK -> R.string.souvera_calendar_week
                                CalendarViewMode.MONTH -> R.string.souvera_calendar_month
                            }
                        ),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }

    if (showCalendarDialog) {
        var draftSelection by remember(calendars, visibleCalendarIds) {
            mutableStateOf(visibleCalendarIds?.toSet())
        }
        SouveraAlertDialog(
            onDismissRequest = { showCalendarDialog = false },
            title = stringResourceCompat(R.string.souvera_calendar_pick),
            confirmText = stringResourceCompat(R.string.common_done),
            dismissText = stringResourceCompat(R.string.common_cancel),
            onConfirm = {
                visibleCalendarIds = draftSelection
                showCalendarDialog = false
            }
        ) {
            Text(
                stringResourceCompat(R.string.souvera_calendar_all),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { draftSelection = null }
                    .padding(vertical = 6.dp)
            )
            calendars.forEach { cal ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val current = draftSelection ?: calendars.map { it.id }.toSet()
                            draftSelection = if (cal.id in current) current - cal.id else current + cal.id
                        }
                        .padding(vertical = 2.dp)
                ) {
                    Checkbox(
                        checked = draftSelection == null || cal.id in (draftSelection ?: emptySet()),
                        onCheckedChange = {
                            val current = draftSelection ?: calendars.map { it.id }.toSet()
                            draftSelection = if (cal.id in current) current - cal.id else current + cal.id
                        }
                    )
                    Box(
                        Modifier
                            .padding(start = 8.dp)
                            .size(10.dp)
                            .background(Color(cal.color), CircleShape)
                    )
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(cal.displayName, style = MaterialTheme.typography.bodyMedium)
                        if (cal.accountName.isNotBlank()) {
                            Text(
                                cal.accountName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Monatsansicht (Outlook-artig)                                       */
/* ------------------------------------------------------------------ */

@Composable
private fun MonthView(
    focusDay: Long,
    events: List<CalendarEvent>,
    calendars: List<CalendarInfo>,
    onPickDay: (Long) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onTitle: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        NavHeader(monthTitle(focusDay), onPrev, onNext, onTitle)
        WeekdayHeaderRow()
        val cells = monthCells(focusDay)
        val byDay = events.groupBy { dayStart(it.begin) }
        Column(Modifier.fillMaxSize()) {
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    week.forEach { day ->
                        DayCell(
                            day = day,
                            inMonth = isSameMonth(day, focusDay),
                            isToday = day == startOfToday(),
                            events = byDay[day].orEmpty(),
                            calendars = calendars,
                            modifier = Modifier.weight(1f),
                            onClick = { onPickDay(day) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: Long,
    inMonth: Boolean,
    isToday: Boolean,
    events: List<CalendarEvent>,
    calendars: List<CalendarInfo>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val dayOfMonth = Calendar.getInstance().apply { timeInMillis = day }.get(Calendar.DAY_OF_MONTH)
    Column(
        modifier
            .fillMaxSize()
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .clickable(onClick = onClick)
            .padding(3.dp)
    ) {
        Box(
            Modifier
                .size(26.dp)
                .background(
                    if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                dayOfMonth.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    isToday -> MaterialTheme.colorScheme.onPrimary
                    inMonth -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.outline
                }
            )
        }
        events.take(3).forEach { event ->
            val color = calendars.firstOrNull { it.id == event.calendarId }?.let { Color(it.color) }
                ?: MaterialTheme.colorScheme.primary
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(color, RoundedCornerShape(2.dp))
                )
                Text(
                    event.title,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 3.dp)
                )
            }
        }
        if (events.size > 3) {
            Text(
                "+${events.size - 3}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* Wochenansicht                                                        */
/* ------------------------------------------------------------------ */

@Composable
private fun WeekView(
    focusDay: Long,
    events: List<CalendarEvent>,
    calendars: List<CalendarInfo>,
    onPickDay: (Long) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    val monday = weekStart(focusDay)
    Column(Modifier.fillMaxSize()) {
        NavHeader(weekTitle(monday), onPrev, onNext)
        val byDay = events.groupBy { dayStart(it.begin) }
        Row(Modifier.fillMaxSize()) {
            (0..6).forEach { offset ->
                val day = addDays(monday, offset)
                Column(
                    Modifier.weight(1f).fillMaxSize()
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (day == startOfToday()) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            )
                            .clickable { onPickDay(day) }
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            weekdayName(day),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            Calendar.getInstance().apply { timeInMillis = day }
                                .get(Calendar.DAY_OF_MONTH).toString(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (day == startOfToday()) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(byDay[day].orEmpty()) { event ->
                            EventChip(event, calendars) { onPickDay(day) }
                        }
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Tagesansicht                                                         */
/* ------------------------------------------------------------------ */

@Composable
private fun DayView(
    focusDay: Long,
    events: List<CalendarEvent>,
    calendars: List<CalendarInfo>,
    onPickEvent: (CalendarEvent) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        NavHeader(dayTitle(focusDay), onPrev, onNext)
        if (events.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResourceCompat(R.string.souvera_calendar_no_events),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(events) { event ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPickEvent(event) }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        val color = calendars.firstOrNull { it.id == event.calendarId }?.let { Color(it.color) }
                            ?: MaterialTheme.colorScheme.primary
                        Box(Modifier.size(10.dp).background(color, CircleShape))
                        Column(Modifier.padding(start = 14.dp)) {
                            Text(
                                if (event.allDay) {
                                    stringResourceCompat(R.string.souvera_all_day)
                                } else {
                                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(event.begin))
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                event.title.ifBlank { stringResourceCompat(R.string.event_untitled) },
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (!event.location.isNullOrBlank()) {
                                Text(
                                    event.location,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventChip(event: CalendarEvent, calendars: List<CalendarInfo>, onClick: () -> Unit) {
    val color = calendars.firstOrNull { it.id == event.calendarId }?.let { Color(it.color) }
        ?: MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 3.dp)
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Box(Modifier.size(5.dp).background(color, CircleShape))
        Column(Modifier.padding(start = 5.dp)) {
            Text(
                event.title,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
            if (!event.allDay) {
                Text(
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(event.begin)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun NavHeader(title: String, onPrev: () -> Unit, onNext: () -> Unit, onTitle: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        TextButtonLight("<", onPrev)
        Spacer(Modifier.weight(1f))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable(enabled = onTitle != null) { onTitle?.invoke() }
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.weight(1f))
        TextButtonLight(">", onNext)
    }
}

@Composable
private fun TextButtonLight(label: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) {
        Text(label, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun WeekdayHeaderRow() {
    Row(Modifier.fillMaxWidth()) {
        (0..6).forEach { offset ->
            Text(
                weekdayShort(offset),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* Datums-Helfer                                                       */
/* ------------------------------------------------------------------ */

private fun rangeFor(mode: CalendarViewMode, focusDay: Long): Pair<Long, Long> = when (mode) {
    CalendarViewMode.DAY -> focusDay to focusDay + DAY_MILLIS
    CalendarViewMode.WEEK -> weekStart(focusDay) to weekStart(focusDay) + 7 * DAY_MILLIS
    CalendarViewMode.MONTH -> {
        val cal = Calendar.getInstance().apply { timeInMillis = focusDay }
        val first = Calendar.getInstance().apply {
            set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val last = Calendar.getInstance().apply {
            set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MONTH, 1)
        }
        first.timeInMillis to last.timeInMillis
    }
}

private fun monthCells(focusDay: Long): List<Long> {
    val cal = Calendar.getInstance().apply { timeInMillis = focusDay }
    val first = Calendar.getInstance().apply {
        set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), 1, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val offset = ((first.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY) + 7) % 7
    val gridStart = addDays(first.timeInMillis, -offset)
    return (0 until MONTH_CELLS).map { addDays(gridStart, it) }
}

private fun weekStart(day: Long): Long {
    val cal = Calendar.getInstance().apply { timeInMillis = day }
    val offset = ((cal.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY) + 7) % 7
    return dayStart(addDays(day, -offset))
}

private fun dayStart(ts: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ts
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun startOfToday(): Long = dayStart(System.currentTimeMillis())

private fun addDays(day: Long, amount: Int): Long = Calendar.getInstance().apply {
    timeInMillis = day
    add(Calendar.DAY_OF_MONTH, amount)
}.timeInMillis

private fun addMonths(day: Long, amount: Int): Long = Calendar.getInstance().apply {
    timeInMillis = day
    add(Calendar.MONTH, amount)
}.timeInMillis

private fun isSameMonth(day: Long, focusDay: Long): Boolean {
    val a = Calendar.getInstance().apply { timeInMillis = day }
    val b = Calendar.getInstance().apply { timeInMillis = focusDay }
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.MONTH) == b.get(Calendar.MONTH)
}

private fun monthTitle(focusDay: Long): String =
    SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(focusDay))

private fun weekTitle(monday: Long): String {
    val end = addDays(monday, 6)
    val fmt = SimpleDateFormat("d. MMM", Locale.getDefault())
    return "${fmt.format(Date(monday))} – ${fmt.format(Date(end))}"
}

private fun dayTitle(day: Long): String =
    SimpleDateFormat("EEEE, d. MMMM", Locale.getDefault()).format(Date(day))

private fun weekdayName(day: Long): String =
    SimpleDateFormat("EEE", Locale.getDefault()).format(Date(day))

private fun weekdayShort(offsetMonday0: Int): String {
    val cal = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        add(Calendar.DAY_OF_MONTH, offsetMonday0)
    }
    return SimpleDateFormat("EE", Locale.getDefault()).format(cal.time).take(2)
}

@Composable
private fun stringResourceCompat(id: Int): String = androidx.compose.ui.res.stringResource(id)

private fun canReadCalendar(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED
