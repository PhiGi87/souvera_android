/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.notes

import androidx.activity.enableEdgeToEdge
import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    private val shownNotes = ArrayList<Note>()
    private var query = ""
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var progress: ProgressBar
    private var client: DavClient? = null
    private var notesUrl: String = ""
    private var pendingNote: Note? = null
    private lateinit var editorLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editorLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                val title = data?.getStringExtra(NoteEditorActivity.EXTRA_TITLE).orEmpty()
                val body = data?.getStringExtra(NoteEditorActivity.EXTRA_BODY).orEmpty()
                saveNote(pendingNote, title, body)
            }
        }
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        // Nahtloser Übergang: Falls ein Gerät Edge-to-Edge nicht umsetzt,
        // färbt die Systemleiste exakt in der obersten Verlaufsfarbe —
        // so wirkt der Verlauf immer bis hinter die Uhr durchgezogen.
        @Suppress("DEPRECATION")
        window.statusBarColor = 0xFF1E4666.toInt()
        // Garantierter Verlauf hinter der Systemleiste: Der Fenster-
        // hintergrund selbst trägt den Gradient — damit erscheint der
        // Verlauf auf JEDEM Gerät (auch ohne Edge-to-Edge) hinter der Uhr.
        @Suppress("DEPRECATION")
        window.setBackgroundDrawableResource(com.owncloud.android.R.drawable.souvera_header_gradient)

        setContentView(R.layout.activity_notes)

        // Einheitliche Souvera-Kopfzeile (identisch zu Mail/Talk/Shield).
        findViewById<androidx.compose.ui.platform.ComposeView>(R.id.notes_header_view).setContent {
            androidx.compose.material3.MaterialTheme {
                com.souvera.workspace.ui.SouveraHomeHeader(
                    onOpenDrawer = { finish() },
                    onOpenSearch = { },
                    searchHint = getString(R.string.souvera_notes_search_hint),
                    navigationBack = true,
                    searchQuery = query,
                    onSearchQueryChange = {
                        query = it
                        refresh()
                    },
                    extraActions = {
                        androidx.compose.material3.IconButton(onClick = { showEditor(null) }) {
                            androidx.compose.material3.Icon(
                                androidx.compose.material.icons.Icons.Filled.Add,
                                contentDescription = getString(R.string.souvera_notes_add),
                                tint = androidx.compose.ui.graphics.Color.White
                            )
                        }
                    }
                )
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
        val note = shownNotes.getOrNull(position) ?: return
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
        pendingNote = existing
        val intent = Intent(this, NoteEditorActivity::class.java).apply {
            putExtra(NoteEditorActivity.EXTRA_TITLE, existing?.title.orEmpty())
            putExtra(NoteEditorActivity.EXTRA_BODY, body)
            putExtra(NoteEditorActivity.EXTRA_IS_NEW, existing == null)
        }
        editorLauncher.launch(intent)
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
                if (ok) {
                    reload()
                } else {
                    setLoading(false)
                    showError()
                }
            }
        }.start()
    }

    private fun confirmDelete(position: Int) {
        val note = shownNotes.getOrNull(position) ?: return
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
                if (ok) {
                    reload()
                } else {
                    setLoading(false)
                    showError()
                }
            }
        }.start()
    }

    private fun refresh() {
        shownNotes.clear()
        shownNotes.addAll(
            if (query.isBlank()) notes
            else notes.filter { it.title.contains(query, ignoreCase = true) }
        )
        adapter.clear()
        adapter.addAll(shownNotes.map { it.title.ifBlank { getString(R.string.souvera_note_untitled) } })
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
        private const val MAX_NAME = 120
    }
}
