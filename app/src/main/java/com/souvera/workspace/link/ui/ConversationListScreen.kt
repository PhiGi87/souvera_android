/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.owncloud.android.R
import com.souvera.workspace.link.net.LinkConversation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(viewModel: LinkViewModel, onOpenDrawer: () -> Unit) {
    val state by viewModel.conversations.collectAsState()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var newChatOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(LIST_REFRESH_MS)
            viewModel.loadConversations()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(stringResource(R.string.drawer_item_link)) },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) { Icon(Icons.Filled.Menu, contentDescription = null) }
                },
                actions = {
                    com.souvera.workspace.status.StatusAction()
                    IconButton(onClick = { shareCallLog(context) }) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.link_share_call_log))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { newChatOpen = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.link_new_chat))
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.link_search_conversations)
            )
            ConversationContent(state, query, viewModel) { c ->
                if (c.type == TYPE_NOTE_TO_SELF) {
                    openNotes(context)
                } else {
                    viewModel.openConversation(c.token, c.displayName)
                }
            }
        }
    }

    if (newChatOpen) {
        NewChatSheet(
            viewModel = viewModel,
            onDismiss = { newChatOpen = false },
            onPick = { suggestion ->
                newChatOpen = false
                viewModel.startConversation(
                    suggestion.id,
                    suggestion.source,
                    suggestion.label.ifBlank {
                        suggestion.id
                    }
                )
            }
        )
    }
}

@Composable
private fun ConversationContent(
    state: LinkUiState<List<LinkConversation>>,
    query: String,
    viewModel: LinkViewModel,
    onOpen: (LinkConversation) -> Unit
) {
    when (state) {
        is LinkUiState.Loading -> Centered { Text(stringResource(R.string.link_loading)) }

        is LinkUiState.Error -> Centered { Text(state.message) }

        is LinkUiState.Success -> {
            val filtered = state.data
                .filter { it.type != TYPE_CHANGELOG }
                .filter { it.displayName.contains(query, ignoreCase = true) }
            if (filtered.isEmpty()) {
                Centered { Text(stringResource(R.string.link_no_conversations)) }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.token }) { c -> 
                        ConversationRow(c, viewModel) { onOpen(c) } 
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(SEARCH_CORNER.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = PAD.dp, vertical = PAD_V.dp)
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewChatSheet(
    viewModel: LinkViewModel,
    onDismiss: () -> Unit,
    onPick: (com.souvera.workspace.link.net.LinkSuggestion) -> Unit
) {
    var search by remember { mutableStateOf("") }
    val results by viewModel.userResults.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.link_new_chat),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = PAD.dp, vertical = PAD_V.dp)
        )
        SearchField(
            value = search,
            onValueChange = {
                search = it
                viewModel.searchUsers(it)
            },
            placeholder = stringResource(R.string.link_search_users)
        )
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = SHEET_BOTTOM.dp)) {
            items(results, key = { it.id + it.source }) { suggestion ->
                val label = suggestion.label.ifBlank { suggestion.id }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { onPick(suggestion) }
                        .padding(horizontal = PAD.dp, vertical = ROW_V.dp)
                ) {
                    Avatar(label)
                    Text(
                        label,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = PAD.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(conversation: LinkConversation, viewModel: LinkViewModel, onClick: () -> Unit) {
    val peerIds by viewModel.peerIdCache.collectAsState()
    val peerId = peerIds[conversation.token]
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = PAD.dp, vertical = ROW_V.dp)
    ) {
        PeerAvatar(name = conversation.displayName, peerId = peerId, viewModel = viewModel)
        Column(Modifier.weight(1f).padding(start = PAD.dp)) {
            Text(
                conversation.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val preview = conversation.lastMessageText()
            if (preview.isNotBlank()) {
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            if (conversation.lastActivity > 0) {
                Text(
                    formatListTime(conversation.lastActivity),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (conversation.unreadMessages > 0) {
                Box(
                    Modifier.padding(top = BADGE_TOP.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = BADGE_H.dp, vertical = BADGE_V.dp)
                ) {
                    Text(
                        conversation.unreadMessages.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun PeerAvatar(name: String, peerId: String?, viewModel: LinkViewModel) {
    val bytes by androidx.compose.runtime.produceState<ByteArray?>(null, peerId) {
        value = peerId?.let { viewModel.loadAvatar(it, AVATAR_SIZE) }
    }
    val bitmap = bytes?.toImageBitmap()
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.size(AVATAR_SIZE.dp).clip(CircleShape)
        )
    } else {
        val palette = AVATAR_PALETTE
        val color = palette[(name.hashCode().mod(palette.size))]
        val initial = name.trim().firstOrNull()?.uppercase() ?: "?"
        Box(
            Modifier.size(AVATAR_SIZE.dp).clip(CircleShape).background(color),
            contentAlignment = Alignment.Center
        ) {
            Text(initial, style = MaterialTheme.typography.titleMedium, color = Color.White)
        }
    }
}

private val AVATAR_PALETTE = listOf(
    Color(0xFF00897B), Color(0xFF3949AB), Color(0xFF8E24AA), Color(0xFFD81B60),
    Color(0xFFF4511E), Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF6D4C41)
)

private fun ByteArray.toImageBitmap(): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    android.graphics.BitmapFactory.decodeByteArray(this, 0, size)?.asImageBitmap()
}.getOrNull()

@Composable
private fun Avatar(name: String) {
    val color = AVATAR_PALETTE[(name.hashCode().mod(AVATAR_PALETTE.size))]
    val initial = name.trim().firstOrNull()?.uppercase() ?: "?"
    Box(
        Modifier.size(AVATAR_SIZE.dp).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(initial, style = MaterialTheme.typography.titleMedium, color = Color.White)
    }
}

private fun shareCallLog(context: android.content.Context) {
    val file = com.souvera.workspace.link.call.CallDebugLog.logFile(context)
    if (!file.exists()) {
        android.widget.Toast.makeText(context, R.string.link_no_call_log, android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context,
        context.getString(R.string.file_provider_authority),
        file
    )
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        android.content.Intent.createChooser(intent, context.getString(R.string.link_share_call_log))
    )
}

private fun openNotes(context: android.content.Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(context, com.souvera.workspace.notes.SouveraNotesActivity::class.java)
        )
    }
}

private fun formatListTime(unixSeconds: Long): String {
    val formatter = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return formatter.format(java.util.Date(unixSeconds * 1000))
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

private const val PAD = 16
private const val PAD_V = 8
private const val ROW_V = 12
private const val SHEET_BOTTOM = 24
private const val BADGE_TOP = 4
private const val BADGE_H = 7
private const val BADGE_V = 2
private const val AVATAR_SIZE = 48
private const val SEARCH_CORNER = 28
private const val LIST_REFRESH_MS = 10_000L
private const val TYPE_CHANGELOG = 4
private const val TYPE_NOTE_TO_SELF = 6
