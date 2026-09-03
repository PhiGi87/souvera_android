/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.calendar

import android.accounts.Account
import android.accounts.AccountManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ContentResolver
import android.os.Bundle
import android.provider.CalendarContract
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.owncloud.android.R
import com.souvera.workspace.dav.SouveraSyncManager
import com.souvera.workspace.link.net.OcsApi
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Create/edit/delete a single calendar event. Writes go through [CalendarRepository] as a normal
 * app (not a sync adapter), so the in-app CalDAV sync uploads them to the server afterwards.
 */
class EventEditActivity : AppCompatActivity() {

    private val repository by lazy { CalendarRepository(this) }
    private val dateFormat = SimpleDateFormat("EEE, d. MMM yyyy", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private var eventId: Long? = null
    private var saving = false
    private val begin = Calendar.getInstance()
    private val end = Calendar.getInstance()

    private lateinit var titleInput: TextInputEditText
    private lateinit var locationInput: TextInputEditText
    private lateinit var allDaySwitch: MaterialSwitch
    private lateinit var linkChannelRow: View
    private lateinit var linkChannelSwitch: MaterialSwitch
    private lateinit var startDateButton: MaterialButton
    private lateinit var startTimeButton: MaterialButton
    private lateinit var endLabel: View
    private lateinit var endRow: View
    private lateinit var endDateButton: MaterialButton
    private lateinit var endTimeButton: MaterialButton

    private val account: Account?
        get() = AccountManager.get(this)
            .getAccountsByType(getString(R.string.account_type))
            .firstOrNull()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = ContextCompat.getColor(this, R.color.primary)
        setContentView(R.layout.activity_event_edit)
        bindViews()
        loadInitial()
        setupToolbar()
        wireInputs()
        render()
    }

    private fun bindViews() {
        titleInput = findViewById(R.id.event_title)
        locationInput = findViewById(R.id.event_location)
        allDaySwitch = findViewById(R.id.event_all_day)
        linkChannelRow = findViewById(R.id.event_link_channel_row)
        linkChannelSwitch = findViewById(R.id.event_link_channel)
        startDateButton = findViewById(R.id.event_start_date)
        startTimeButton = findViewById(R.id.event_start_time)
        endLabel = findViewById(R.id.event_end_label)
        endRow = findViewById(R.id.event_end_row)
        endDateButton = findViewById(R.id.event_end_date)
        endTimeButton = findViewById(R.id.event_end_time)
    }

    private fun loadInitial() {
        val id = intent.getLongExtra(EXTRA_EVENT_ID, INVALID_ID)
        if (id >= 0) {
            eventId = id
            // Link-Kanal ist nur bei der Neuanlage relevant.
            linkChannelRow.visibility = View.GONE
            repository.loadEvent(id)?.let { draft ->
                titleInput.setText(draft.title)
                locationInput.setText(draft.location)
                allDaySwitch.isChecked = draft.allDay
                begin.timeInMillis = draft.begin
                end.timeInMillis = draft.end
            }
        } else {
            val dayBegin = intent.getLongExtra(EXTRA_DAY_BEGIN, System.currentTimeMillis())
            begin.timeInMillis = startOfDayAtDefaultHour(dayBegin)
            end.timeInMillis = begin.timeInMillis + DEFAULT_DURATION_MILLIS
        }
    }

    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.event_toolbar)
        toolbar.title = getString(if (eventId == null) R.string.event_new_title else R.string.event_edit_title)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.menu.add(0, MENU_SAVE, 0, R.string.event_save).apply {
            setIcon(android.R.drawable.ic_menu_save)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        if (eventId != null) {
            toolbar.menu.add(0, MENU_DELETE, 1, R.string.event_delete).apply {
                setIcon(android.R.drawable.ic_menu_delete)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
        }
        toolbar.setOnMenuItemClickListener { item -> onMenu(item.itemId) }
    }

    private fun onMenu(itemId: Int): Boolean = when (itemId) {
        MENU_SAVE -> {
            onSave()
            true
        }

        MENU_DELETE -> {
            onDelete()
            true
        }

        else -> false
    }

    private fun wireInputs() {
        allDaySwitch.setOnCheckedChangeListener { _, _ -> render() }
        startDateButton.setOnClickListener { pickDate(begin) }
        startTimeButton.setOnClickListener { pickTime(begin) }
        endDateButton.setOnClickListener { pickDate(end) }
        endTimeButton.setOnClickListener { pickTime(end) }
    }

    private fun render() {
        startDateButton.text = dateFormat.format(begin.time)
        startTimeButton.text = timeFormat.format(begin.time)
        endDateButton.text = dateFormat.format(end.time)
        endTimeButton.text = timeFormat.format(end.time)
        val timeVisibility = if (allDaySwitch.isChecked) View.GONE else View.VISIBLE
        startTimeButton.visibility = timeVisibility
        endLabel.visibility = timeVisibility
        endRow.visibility = timeVisibility
    }

    private fun pickDate(target: Calendar) {
        DatePickerDialog(
            this,
            { _, year, month, day ->
                target.set(Calendar.YEAR, year)
                target.set(Calendar.MONTH, month)
                target.set(Calendar.DAY_OF_MONTH, day)
                keepOrder()
                render()
            },
            target.get(Calendar.YEAR),
            target.get(Calendar.MONTH),
            target.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun pickTime(target: Calendar) {
        TimePickerDialog(
            this,
            { _, hour, minute ->
                target.set(Calendar.HOUR_OF_DAY, hour)
                target.set(Calendar.MINUTE, minute)
                keepOrder()
                render()
            },
            target.get(Calendar.HOUR_OF_DAY),
            target.get(Calendar.MINUTE),
            true
        ).show()
    }

    private fun keepOrder() {
        if (end.timeInMillis <= begin.timeInMillis) {
            end.timeInMillis = begin.timeInMillis + DEFAULT_DURATION_MILLIS
        }
    }

    private fun onSave() {
        if (saving) return
        val title = titleInput.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) {
            Toast.makeText(this, R.string.event_title_required, Toast.LENGTH_SHORT).show()
            return
        }
        val currentAccount = account ?: run {
            Toast.makeText(this, R.string.event_no_calendar, Toast.LENGTH_LONG).show()
            return
        }
        val location = locationInput.text?.toString()?.trim().orEmpty()
        val createLink = eventId == null && linkChannelSwitch.isChecked
        val draft = if (allDaySwitch.isChecked) {
            val dayStart = utcMidnight(begin)
            EventDraft(eventId, title, location, dayStart, dayStart + DAY_MILLIS, allDay = true, createLinkChannel = createLink)
        } else {
            EventDraft(eventId, title, location, begin.timeInMillis, end.timeInMillis, allDay = false, createLinkChannel = createLink)
        }
        saving = true
        if (createLink) {
            val dav = SouveraSyncManager(this).resolve(currentAccount)
            if (dav == null) {
                // Ohne auflösbare Anmeldedaten den Termin ohne Link speichern.
                saveDraft(currentAccount, draft)
                Toast.makeText(this, R.string.event_link_channel_failed, Toast.LENGTH_SHORT).show()
                return
            }
            // OcsApi ist blockierendes HTTP — niemals auf dem Main-Thread.
            Thread {
                val token = runCatching { OcsApi(dav).createConversation(title, LINK_CHANNEL_ROOM_TYPE) }.getOrNull()
                val linkDraft = token?.let { draft.copy(description = "${dav.baseUrl.trimEnd('/')}/call/$it") } ?: draft
                runOnUiThread {
                    saveDraft(currentAccount, linkDraft)
                    if (token == null) {
                        Toast.makeText(this, R.string.event_link_channel_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        } else {
            saveDraft(currentAccount, draft)
        }
    }

    private fun saveDraft(currentAccount: Account, draft: EventDraft) {
        if (repository.save(currentAccount, draft)) {
            requestSync(currentAccount)
            Toast.makeText(this, R.string.event_saved, Toast.LENGTH_SHORT).show()
            finish()
        } else {
            saving = false
            Toast.makeText(this, R.string.event_no_calendar, Toast.LENGTH_LONG).show()
        }
    }

    private fun onDelete() {
        eventId?.let { id ->
            repository.delete(id)
            account?.let { requestSync(it) }
            Toast.makeText(this, R.string.event_deleted, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun requestSync(account: Account) {
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        ContentResolver.requestSync(account, CalendarContract.AUTHORITY, extras)
    }

    private fun startOfDayAtDefaultHour(dayBegin: Long): Long = Calendar.getInstance().apply {
        timeInMillis = dayBegin
        set(Calendar.HOUR_OF_DAY, DEFAULT_HOUR)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun utcMidnight(local: Calendar): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis

    companion object {
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_DAY_BEGIN = "day_begin"
        private const val INVALID_ID = -1L
        private const val MENU_SAVE = 1
        private const val MENU_DELETE = 2
        private const val DEFAULT_HOUR = 9
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        private const val DEFAULT_DURATION_MILLIS = 60 * 60 * 1000L
        private const val LINK_CHANNEL_ROOM_TYPE = 2
    }
}
