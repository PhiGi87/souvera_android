/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.notes

import android.accounts.AccountManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.owncloud.android.R
import com.souvera.workspace.dav.DavClient
import com.souvera.workspace.dav.SouveraSyncManager

/**
 * Notes stored as text files in the "Notes" folder of the user's files on the server
 * (same location the Nextcloud Notes app uses), read and written over WebDAV. Nothing is
 * kept only on the device, so notes stay in sync with the workspace.
 */
class SouveraNotesActivity : AppCompatActivity() {

    private data class Note(val href: String, val title: String)

    private val notes = ArrayList<Note>()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var progress: ProgressBar
    private var client: DavClient? = null
    private var notesUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = ContextCompat.getColor(this, R.color.primary)
        setContentView(R.layout.activity_notes)

        val toolbar = findViewById<MaterialToolbar>(R.id.notes_toolbar)
        toolbar.title = getString(R.string.souvera_notes_title)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.menu.add(0, MENU_ADD, 0, R.string.souvera_notes_add).apply {
            setIcon(android.R.drawable.ic_menu_add)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == MENU_ADD) {
                showEditor(null)
                true
            } else {
                false
            }
        }

        progress = findViewById(R.id.notes_progress)
        val list = findViewById<ListView>(R.id.notes_list)
        list.emptyView = findViewById<TextView>(R.id.notes_empty)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> openNote(position) }
        list.setOnItemLongClickListener { _, _, position, _ ->
            confirmDelete(position)
            true
        }

        val account = AccountManager.get(this)
            .getAccountsByType(getString(R.string.account_type))
            .firstOrNull()
        val dav = account?.let { SouveraSyncManager(this).resolve(it) }
        if (dav == null) {
            Toast.makeText(this, R.string.souvera_no_account, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        client = DavClient(dav.baseUrl, dav.username, dav.password)
        val folder = getString(R.string.souvera_notes_folder)
        notesUrl = "${dav.baseUrl}/remote.php/dav/files/${Uri.encode(dav.username)}/$folder/"
        reload()
    }

    private fun reload() {
        val dav = client ?: return
        setLoading(true)
        Thread {
            val result = runCatching {
                dav.mkcol(notesUrl)
                dav.list(notesUrl)
                    .filter { !it.isCollection && isTextFile(it.href) }
                    .map { Note(it.href, titleOf(it.href)) }
                    .sortedBy { it.title.lowercase() }
            }
            onUi {
                setLoading(false)
                result.onSuccess {
                    notes.clear()
                    notes.addAll(it)
                    refresh()
                }.onFailure { showError() }
            }
        }.start()
    }

    private fun openNote(position: Int) {
        val dav = client ?: return
        val note = notes[position]
        setLoading(true)
        Thread {
            val content = runCatching { dav.get(note.href) }.getOrNull()
            onUi {
                setLoading(false)
                showEditor(note, content ?: "")
            }
        }.start()
    }

    private fun showEditor(existing: Note?, body: String = "") {
        val padding = resources.getDimensionPixelSize(R.dimen.standard_padding)
        val titleInput = EditText(this).apply {
            hint = getString(R.string.souvera_note_title_hint)
            setText(existing?.title ?: "")
            setSingleLine()
        }
        val bodyInput = EditText(this).apply {
            hint = getString(R.string.souvera_note_hint)
            setText(body)
            setLines(EDIT_LINES)
            gravity = Gravity.TOP or Gravity.START
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(titleInput)
            addView(bodyInput)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.souvera_notes_add else R.string.souvera_note_edit)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                saveNote(existing, titleInput.text.toString(), bodyInput.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveNote(existing: Note?, title: String, body: String) {
        val dav = client ?: return
        val newTitle = titleOf(fileName(title))
        val unchanged = existing != null && newTitle == existing.title
        val target = if (unchanged && existing != null) existing.href else notesUrl + Uri.encode(fileName(title))
        setLoading(true)
        Thread {
            val ok = runCatching {
                val result = dav.put(target, body, "text/markdown; charset=utf-8")
                if (result.success && existing != null && !unchanged) {
                    dav.delete(existing.href)
                }
                result.success
            }.getOrDefault(false)
            onUi {
                if (ok) reload() else { setLoading(false); showError() }
            }
        }.start()
    }

    private fun confirmDelete(position: Int) {
        val note = notes[position]
        AlertDialog.Builder(this)
            .setTitle(R.string.souvera_note_delete)
            .setMessage(note.title)
            .setPositiveButton(R.string.souvera_delete) { _, _ -> deleteNote(note) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteNote(note: Note) {
        val dav = client ?: return
        setLoading(true)
        Thread {
            val ok = runCatching { dav.delete(note.href) }.getOrDefault(false)
            onUi {
                if (ok) reload() else { setLoading(false); showError() }
            }
        }.start()
    }

    private fun refresh() {
        adapter.clear()
        adapter.addAll(notes.map { it.title.ifBlank { getString(R.string.souvera_note_untitled) } })
        adapter.notifyDataSetChanged()
    }

    private fun setLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun showError() {
        Toast.makeText(this, R.string.souvera_notes_error, Toast.LENGTH_LONG).show()
    }

    private fun onUi(block: () -> Unit) = runOnUiThread {
        if (!isFinishing && !isDestroyed) block()
    }

    private fun fileName(title: String): String {
        val base = title.trim().replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").take(MAX_NAME)
        return base.ifEmpty { getString(R.string.souvera_notes_default_name) } + ".md"
    }

    private fun titleOf(href: String): String {
        val segment = Uri.decode(href.trimEnd('/').substringAfterLast('/'))
        return segment.replace(Regex("\\.(txt|md|markdown)$", RegexOption.IGNORE_CASE), "")
    }

    private fun isTextFile(href: String): Boolean =
        href.substringAfterLast('/').matches(Regex(".*\\.(txt|md|markdown)$", RegexOption.IGNORE_CASE))

    companion object {
        private const val MENU_ADD = 1
        private const val EDIT_LINES = 8
        private const val MAX_NAME = 120
    }
}
