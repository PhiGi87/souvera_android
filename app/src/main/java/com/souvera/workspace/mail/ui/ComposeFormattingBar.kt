/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.RichTextState

/**
 * Reusable WYSIWYG formatting strip. Bold/italic/underline and the list toggles act directly on the
 * [RichTextState]; each button highlights while its style is active at the cursor so the user sees
 * the current formatting at a glance. Shared by the mail composer and the note editor.
 */
@Composable
fun RichFormattingToolbar(state: RichTextState, trailing: @Composable () -> Unit = {}) {
    val span = state.currentSpanStyle
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = BAR_PADDING.dp)
    ) {
        FormatToggle(active = span.fontWeight == FontWeight.Bold, onClick = { state.toggleSpanStyle(spanBold) }) {
            Text(BOLD_LABEL, fontWeight = FontWeight.Bold, color = it)
        }
        FormatToggle(active = span.fontStyle == FontStyle.Italic, onClick = { state.toggleSpanStyle(spanItalic) }) {
            Text(ITALIC_LABEL, fontStyle = FontStyle.Italic, color = it)
        }
        FormatToggle(
            active = span.textDecoration == TextDecoration.Underline,
            onClick = { state.toggleSpanStyle(spanUnderline) }
        ) {
            Text(UNDERLINE_LABEL, textDecoration = TextDecoration.Underline, color = it)
        }
        FormatToggle(active = state.isUnorderedList, onClick = { state.toggleUnorderedList() }) {
            Text(BULLET_LABEL, color = it)
        }
        FormatToggle(active = state.isOrderedList, onClick = { state.toggleOrderedList() }) {
            Text(NUMBERED_LABEL, color = it)
        }
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** Mail composer variant: the shared toolbar plus an attach action (paperclip icon only). */
@Composable
fun FormattingBar(state: RichTextState, onAttach: () -> Unit) {
    RichFormattingToolbar(state) {
        IconButton(onClick = onAttach) {
            Text(ATTACH_LABEL, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun FormatToggle(active: Boolean, onClick: () -> Unit, label: @Composable (Color) -> Unit) {
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    TextButton(onClick = onClick) { label(tint) }
}

private const val BAR_PADDING = 4
private const val BOLD_LABEL = "B"
private const val ITALIC_LABEL = "I"
private const val UNDERLINE_LABEL = "U"
private const val BULLET_LABEL = "• —"
private const val NUMBERED_LABEL = "1."
private const val ATTACH_LABEL = "📎"

private val spanBold = SpanStyle(fontWeight = FontWeight.Bold)
private val spanItalic = SpanStyle(fontStyle = FontStyle.Italic)
private val spanUnderline = SpanStyle(textDecoration = TextDecoration.Underline)
