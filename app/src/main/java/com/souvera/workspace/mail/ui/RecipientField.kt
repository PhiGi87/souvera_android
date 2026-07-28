/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.souvera.workspace.mail.ContactSuggestionSource
import com.souvera.workspace.mail.model.RecipientSuggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Recipient input for the composer: styled like the other borderless form rows, but the token
 * currently being typed (after the last comma/semicolon) is matched against the device contacts
 * and offered in a non-focusable dropdown, so typing continues uninterrupted.
 */
@Composable
fun RecipientField(
    value: String,
    onValueChange: (String) -> Unit,
    prefixText: String,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val context = LocalContext.current
    val source = remember { ContactSuggestionSource(context) }
    var suggestions by remember { mutableStateOf(emptyList<RecipientSuggestion>()) }
    val token = value.substringAfterLast(',').substringAfterLast(';').trim()

    LaunchedEffect(token) {
        if (token.length < MIN_QUERY_LENGTH) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(DEBOUNCE_MS)
        val found = withContext(Dispatchers.IO) { source.search(token, MAX_SUGGESTIONS) }
        suggestions =
            if (found.size == 1 && found.first().email.equals(token, ignoreCase = true)) {
                emptyList()
            } else {
                found
            }
    }

    Box(modifier) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            prefix = {
                Text(
                    prefixText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = PREFIX_GAP.dp)
                )
            },
            singleLine = true,
            colors = transparentTextFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
        DropdownMenu(
            expanded = suggestions.isNotEmpty(),
            onDismissRequest = { suggestions = emptyList() },
            properties = PopupProperties(focusable = false)
        ) {
            suggestions.forEach { suggestion ->
                DropdownMenuItem(
                    text = { SuggestionLabel(suggestion) },
                    onClick = {
                        onValueChange(replaceLastToken(value, suggestion.email))
                        suggestions = emptyList()
                    }
                )
            }
        }
    }
}

@Composable
private fun SuggestionLabel(suggestion: RecipientSuggestion) {
    Column {
        Text(suggestion.displayName ?: suggestion.email, style = MaterialTheme.typography.bodyLarge)
        if (suggestion.displayName != null) {
            Text(
                suggestion.email,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun replaceLastToken(value: String, email: String): String {
    val separatorIndex = maxOf(value.lastIndexOf(','), value.lastIndexOf(';'))
    return if (separatorIndex < 0) email else value.substring(0, separatorIndex + 1) + " " + email
}

private const val MIN_QUERY_LENGTH = 2
private const val DEBOUNCE_MS = 150L
private const val MAX_SUGGESTIONS = 6
private const val PREFIX_GAP = 8
