/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.notes

import androidx.activity.enableEdgeToEdge
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.owncloud.android.R

/**
 * Full-screen WYSIWYG editor for a single note. Editing happens on a rich text surface; the note is
 * persisted as Markdown so it stays readable in the Nextcloud Notes app and any plain text client.
 * The activity only edits text - reading and writing over WebDAV stays in [SouveraNotesActivity],
 * which launches this screen for a result and receives the new title and Markdown body back.
 */
class NoteEditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        val initialTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val initialBody = intent.getStringExtra(EXTRA_BODY).orEmpty()
        val isNew = intent.getBooleanExtra(EXTRA_IS_NEW, true)

        setContent {
            SouveraNoteTheme {
                NoteEditorScreen(
                    initialTitle = initialTitle,
                    initialMarkdown = initialBody,
                    isNew = isNew,
                    onClose = { finish() },
                    onSave = { title, markdown -> finishWithResult(title, markdown) }
                )
            }
        }
    }

    private fun finishWithResult(title: String, markdown: String) {
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BODY, markdown)
            }
        )
        finish()
    }

    @Composable
    private fun SouveraNoteTheme(content: @Composable () -> Unit) {
        val primary = Color(ContextCompat.getColor(this, R.color.primary))
        val scheme = if (isSystemInDarkTheme()) {
            darkColorScheme(primary = primary)
        } else {
            lightColorScheme(primary = primary)
        }
        MaterialTheme(colorScheme = scheme, content = content)
    }

    companion object {
        const val EXTRA_TITLE = "note_title"
        const val EXTRA_BODY = "note_body"
        const val EXTRA_IS_NEW = "note_is_new"
    }
}
