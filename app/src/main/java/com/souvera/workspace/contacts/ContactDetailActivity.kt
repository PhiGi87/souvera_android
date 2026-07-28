/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.contacts

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.owncloud.android.R

/** Shows one contact's email addresses (tap to compose) and phone numbers (tap to dial). */
class ContactDetailActivity : AppCompatActivity() {

    private val actions = mutableListOf<Pair<String, Intent>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = ContextCompat.getColor(this, R.color.primary)
        setContentView(R.layout.activity_contact_detail)

        val name = intent.getStringExtra(EXTRA_CONTACT_NAME).orEmpty()
        val toolbar = findViewById<MaterialToolbar>(R.id.contact_detail_toolbar)
        toolbar.title = name
        toolbar.setNavigationOnClickListener { finish() }

        val detail = ContactsRepository(this).loadDetail(intent.getLongExtra(EXTRA_CONTACT_ID, INVALID_ID))
        detail.emails.forEach { email ->
            actions += email to Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))
        }
        detail.phones.forEach { phone ->
            actions += phone to Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
        }

        val list = findViewById<ListView>(R.id.contact_detail_list)
        list.emptyView = findViewById<View>(R.id.contact_detail_empty)
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, actions.map { it.first })
        list.setOnItemClickListener { _, _, position, _ -> launch(actions[position].second) }
    }

    private fun launch(intent: Intent) {
        try {
            startActivity(intent)
        } catch (ignored: ActivityNotFoundException) {
            // no mail/dial app installed - nothing to do
        }
    }

    companion object {
        const val EXTRA_CONTACT_ID = "contact_id"
        const val EXTRA_CONTACT_NAME = "contact_name"
        private const val INVALID_ID = -1L
    }
}
