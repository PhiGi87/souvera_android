/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.owncloud.android.R

@Composable
fun MailSearchScreen(viewModel: MailViewModel) {
    val query by viewModel.searchQuery.collectAsState()
    val results by viewModel.searchResults.collectAsState()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = com.souvera.workspace.ui.SouveraContentBackground(),
        topBar = { SearchInputBar(query, viewModel::setSearchQuery) { viewModel.back() } }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                query.isBlank() ->
                    MailPlaceholder(stringResource(R.string.mail_search_hint), Icons.Filled.Search)

                results.isEmpty() ->
                    MailPlaceholder(stringResource(R.string.mail_no_results), Icons.Filled.Search)

                else ->
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(results, key = { it.rowId }) { message ->
                            MessageRow(
                                message = message,
                                onClick = { viewModel.openMessage(message) },
                                onToggleFlag = { viewModel.toggleFlagged(message, !message.isFlagged) }
                            )
                        }
                    }
            }
        }
    }
}

@Composable
private fun SearchInputBar(query: String, onQueryChange: (String) -> Unit, onBack: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    com.souvera.workspace.ui.SouveraHeader {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(com.souvera.workspace.ui.SOUVERA_HEADER_HEIGHT.dp)
                .padding(horizontal = BAR_PADDING.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = {
                    Text(
                        stringResource(R.string.mail_search_hint),
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f)
                    )
                },
                singleLine = true,
                textStyle = androidx.compose.material3.MaterialTheme.typography.bodyLarge.copy(
                    color = androidx.compose.ui.graphics.Color.White
                ),
                colors = transparentTextFieldColors().copy(
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    cursorColor = androidx.compose.ui.graphics.Color.White,
                    focusedTextColor = androidx.compose.ui.graphics.Color.White,
                    unfocusedTextColor = androidx.compose.ui.graphics.Color.White
                ),
                modifier = Modifier.weight(1f).focusRequester(focusRequester)
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.mail_search_clear))
                }
            }
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

private const val BAR_PADDING = 4
