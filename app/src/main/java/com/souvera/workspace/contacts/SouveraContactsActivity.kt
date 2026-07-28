/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.contacts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.owncloud.android.R

/**
 * Native contact list backed by the Android Contacts Provider (kept filled by the in-app CardDAV
 * sync). Tapping a contact opens [ContactDetailActivity] with its addresses and phone numbers.
 */
class SouveraContactsActivity : AppCompatActivity() {

    private val repository by lazy { ContactsRepository(this) }
    private lateinit var adapter: ArrayAdapter<String>
    private var contacts = emptyList<ContactSummary>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = ContextCompat.getColor(this, R.color.primary)
        setContentView(R.layout.activity_souvera_contacts)

        val toolbar = findViewById<MaterialToolbar>(R.id.contacts_toolbar)
        toolbar.title = getString(R.string.drawer_item_contacts)
        toolbar.setNavigationOnClickListener { finish() }

        val list = findViewById<ListView>(R.id.contacts_list)
        list.emptyView = findViewById<View>(R.id.contacts_empty)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            contacts.getOrNull(position)?.let { openDetail(it) }
        }

        ensurePermission()
        loadContacts()
    }

    override fun onResume() {
        super.onResume()
        loadContacts()
    }

    private fun loadContacts() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        contacts = repository.loadContacts()
        adapter.clear()
        adapter.addAll(contacts.map { it.displayName })
        adapter.notifyDataSetChanged()
    }

    private fun openDetail(contact: ContactSummary) {
        startActivity(
            Intent(this, ContactDetailActivity::class.java).apply {
                putExtra(ContactDetailActivity.EXTRA_CONTACT_ID, contact.id)
                putExtra(ContactDetailActivity.EXTRA_CONTACT_NAME, contact.displayName)
            }
        )
    }

    private fun ensurePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_CONTACTS),
                REQUEST_CONTACTS
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CONTACTS) {
            loadContacts()
        }
    }

    companion object {
        private const val REQUEST_CONTACTS = 4712
    }
}
