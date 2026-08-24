/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.calendar

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.owncloud.android.R
import com.souvera.workspace.ui.SouveraContentBackground

/**
 * Souvera-Kalender: Outlook-artige Monats-/Wochen-/Tagesansicht mit
 * Kalender-Auswahl und Ansichts-Wechsel über Dialoge. Die Termine kommen aus
 * dem Android Calendar Provider (befüllt durch den CalDAV-Sync-Adapter).
 */
class CalendarActivity : AppCompatActivity() {

    private val repository by lazy { CalendarRepository(this) }
    private var reloadTrigger by mutableIntStateOf(0)

    private val account: Account?
        get() = AccountManager.get(this)
            .getAccountsByType(getString(R.string.account_type))
            .firstOrNull()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        @Suppress("DEPRECATION")
        window.statusBarColor = 0xFF1E4666.toInt()
        setContentView(R.layout.activity_calendar)

        ensurePermissions()
        enableSync()

        findViewById<ComposeView>(R.id.calendar_compose_view).setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = SouveraContentBackground()) {
                    CalendarScreen(
                        repository = repository,
                        reloadTrigger = reloadTrigger,
                        onOpenEditor = { eventId, dayBegin ->
                            val intent = Intent(this, EventEditActivity::class.java)
                            if (eventId != null) {
                                intent.putExtra(EventEditActivity.EXTRA_EVENT_ID, eventId)
                            } else {
                                intent.putExtra(EventEditActivity.EXTRA_DAY_BEGIN, dayBegin)
                            }
                            startActivity(intent)
                        },
                        onBack = { finish() },
                        onSync = { triggerSync() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Rückkehr aus dem Editor: Kalender neu laden.
        reloadTrigger++
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            reloadTrigger++
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 4711
    }
}
