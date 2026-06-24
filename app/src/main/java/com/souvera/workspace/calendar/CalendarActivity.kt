/*
 * Souvera Workspace - Android Client
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.calendar

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CalendarView
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.owncloud.android.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Native Souvera Workspace calendar screen. Reads events from the Android
 * Calendar Provider (populated by the CalDAV sync adapter) and lets the user
 * trigger a manual contacts & calendar sync.
 */
class CalendarActivity : AppCompatActivity() {

    private lateinit var eventsList: ListView
    private lateinit var emptyView: View
    private lateinit var adapter: ArrayAdapter<String>
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val account: Account?
        get() = AccountManager.get(this)
            .getAccountsByType(getString(R.string.account_type))
            .firstOrNull()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calendar)

        val toolbar = findViewById<MaterialToolbar>(R.id.calendar_toolbar)
        toolbar.title = getString(R.string.drawer_item_calendar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.menu.add(0, MENU_SYNC, 0, R.string.souvera_sync_action).apply {
            setIcon(android.R.drawable.ic_popup_sync)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == MENU_SYNC) {
                triggerSync()
                true
            } else {
                false
            }
        }

        eventsList = findViewById(R.id.events_list)
        emptyView = findViewById(R.id.events_empty)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        eventsList.adapter = adapter
        eventsList.emptyView = emptyView

        val calendarView = findViewById<CalendarView>(R.id.calendar_view)
        calendarView.setOnDateChangeListener { _, year, month, day ->
            loadEvents(year, month, day)
        }

        ensurePermissions()
        enableSync()

        val now = Calendar.getInstance()
        loadEvents(now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH))
    }

    @SuppressLint("MissingPermission")
    private fun loadEvents(year: Int, month: Int, day: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val begin = Calendar.getInstance().apply {
            set(year, month, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val end = begin + DAY_MILLIS

        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY
        )

        val items = ArrayList<String>()
        val cursor = CalendarContract.Instances.query(contentResolver, projection, begin, end)
        cursor?.use {
            while (it.moveToNext()) {
                val title = it.getString(0) ?: "(no title)"
                val start = it.getLong(1)
                val location = it.getString(2)
                val allDay = it.getInt(3) == 1
                val time = if (allDay) getString(R.string.souvera_all_day) else timeFormat.format(Date(start))
                items.add(if (location.isNullOrBlank()) "$time  •  $title" else "$time  •  $title\n$location")
            }
        }

        adapter.clear()
        adapter.addAll(items)
        adapter.notifyDataSetChanged()
    }

    private fun enableSync() {
        val acc = account ?: return
        ContentResolver.setIsSyncable(acc, CalendarContract.AUTHORITY, 1)
        ContentResolver.setSyncAutomatically(acc, CalendarContract.AUTHORITY, true)
        ContentResolver.setIsSyncable(acc, android.provider.ContactsContract.AUTHORITY, 1)
        ContentResolver.setSyncAutomatically(acc, android.provider.ContactsContract.AUTHORITY, true)
    }

    private fun triggerSync() {
        val acc = account
        if (acc == null) {
            Toast.makeText(this, R.string.souvera_no_account, Toast.LENGTH_LONG).show()
            return
        }
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        ContentResolver.requestSync(acc, CalendarContract.AUTHORITY, extras)
        ContentResolver.requestSync(acc, android.provider.ContactsContract.AUTHORITY, extras)
        Toast.makeText(this, R.string.souvera_sync_started, Toast.LENGTH_SHORT).show()
    }

    private fun ensurePermissions() {
        val needed = arrayOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            val now = Calendar.getInstance()
            loadEvents(now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH))
        }
    }

    companion object {
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        private const val REQUEST_PERMISSIONS = 4711
        private const val MENU_SYNC = 1
    }
}
