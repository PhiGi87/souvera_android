/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.notes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditorDefaults
import com.owncloud.android.R
import com.souvera.workspace.mail.ui.RichFormattingToolbar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    initialTitle: String,
    initialMarkdown: String,
    isNew: Boolean,
    onClose: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    val richState = rememberRichTextState()

    LaunchedEffect(richState) {
        if (initialMarkdown.isNotBlank()) richState.setMarkdown(initialMarkdown)
    }

    Scaffold(
        topBar = {
            NoteEditorTopBar(
                isNew = isNew,
                onClose = onClose,
                onSave = { onSave(title, richState.toMarkdown()) }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text(stringResource(R.string.souvera_note_title_hint)) },
                singleLine = true,
                colors = transparentColors(),
                modifier = Modifier.fillMaxWidth()
            )
            HorizontalDivider()
            RichTextEditor(
                state = richState,
                placeholder = { Text(stringResource(R.string.souvera_note_hint)) },
                colors = RichTextEditorDefaults.richTextEditorColors(
                    containerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = EDITOR_PADDING.dp)
            )
            HorizontalDivider()
            RichFormattingToolbar(richState)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEditorTopBar(isNew: Boolean, onClose: () -> Unit, onSave: () -> Unit) {
    com.souvera.workspace.ui.SouveraTopBar(
        title = { Text(stringResource(if (isNew) R.string.souvera_notes_add else R.string.souvera_note_edit)) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
        },
        actions = {
            IconButton(onClick = onSave) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = stringResource(R.string.common_save),
                    tint = androidx.compose.ui.graphics.Color.White
                )
            }
        }
    )
}

@Composable
private fun transparentColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent
)

private const val EDITOR_PADDING = 8
