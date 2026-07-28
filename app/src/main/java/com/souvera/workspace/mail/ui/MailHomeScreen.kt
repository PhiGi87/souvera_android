/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.owncloud.android.R
import com.souvera.workspace.mail.db.entity.MailboxEntity
import com.souvera.workspace.mail.db.entity.MessageEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailHomeScreen(viewModel: MailViewModel, onOpenDrawer: () -> Unit, onOpenSettings: () -> Unit) {
    val messagesState by viewModel.messages.collectAsState()
    val mailboxesState by viewModel.mailboxes.collectAsState()
    val currentMailbox by viewModel.currentMailbox.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val fromAddress by viewModel.fromAddress.collectAsState()
    var folderSheetOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(currentMailbox?.id) {
        while (currentMailbox != null) {
            kotlinx.coroutines.delay(AUTO_REFRESH_INTERVAL_MS)
            viewModel.autoRefresh()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            MailHomeTopArea(
                mailbox = resolvedCurrent(mailboxesState, currentMailbox),
                onOpenDrawer = onOpenDrawer,
                onOpenSearch = { viewModel.navigate(MailRoute.Search) },
                onOpenSettings = onOpenSettings,
                onOpenFolders = { folderSheetOpen = true }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.navigate(MailRoute.Compose()) },
                icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                text = { Text(stringResource(R.string.mail_compose)) }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            MailMessageList(messagesState, viewModel)
        }
    }

    if (folderSheetOpen) {
        MailFolderSheet(
            state = mailboxesState,
            currentId = currentMailbox?.id,
            personalLabel = fromAddress,
            onSelect = {
                viewModel.selectMailbox(it)
                folderSheetOpen = false
            },
            onDismiss = { folderSheetOpen = false }
        )
    }
}

@Composable
private fun MailHomeTopArea(
    mailbox: MailboxEntity?,
    onOpenDrawer: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFolders: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = SCREEN_PADDING.dp)
    ) {
        MailSearchBar(onOpenDrawer, onOpenSearch, onOpenSettings)
        if (mailbox != null) {
            MailFolderSelector(mailbox, onOpenFolders)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MailSearchBar(onOpenDrawer: () -> Unit, onOpenSearch: () -> Unit, onOpenSettings: () -> Unit) {
    Surface(
        onClick = onOpenSearch,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = PILL_PADDING_VERTICAL.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.mail_open_drawer))
            }
            Text(
                stringResource(R.string.mail_search_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            com.souvera.workspace.status.StatusAction()
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.mail_settings),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MailFolderSelector(mailbox: MailboxEntity, onOpenFolders: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenFolders)
            .padding(horizontal = SELECTOR_PADDING.dp, vertical = SELECTOR_PADDING_VERTICAL.dp)
    ) {
        Icon(
                mailbox.kind.folderIcon(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(SELECTOR_ICON_SIZE.dp)
        )
        Spacer(Modifier.width(SELECTOR_GAP.dp))
        val roleName = mailboxDisplayName(mailbox)
        val owner = mailbox.ownerIdentity
        Text(
            if (owner != null) "$roleName · $owner" else roleName,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(
            Icons.Filled.ArrowDropDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        if (mailbox.unreadCount > 0) {
            Text(
                mailbox.unreadCount.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun MailMessageList(state: MailUiState<List<MessageEntity>>, viewModel: MailViewModel) {
    when (state) {
        is MailUiState.Loading -> MailLoading()

        is MailUiState.Error -> MailPlaceholder(state.message, Icons.Filled.Warning)

        is MailUiState.Success ->
            if (state.data.isEmpty()) {
                MailPlaceholder(stringResource(R.string.mail_no_messages), Icons.Filled.MailOutline)
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.data, key = { it.rowId }) { message ->
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

private fun resolvedCurrent(state: MailUiState<List<MailboxEntity>>, current: MailboxEntity?): MailboxEntity? =
    when (state) {
        is MailUiState.Success -> state.data.firstOrNull { it.id == current?.id } ?: current
        else -> current
    }

private const val AUTO_REFRESH_INTERVAL_MS = 60_000L
private const val SCREEN_PADDING = 12
private const val PILL_PADDING_VERTICAL = 8
private const val SELECTOR_PADDING = 4
private const val SELECTOR_PADDING_VERTICAL = 6
private const val SELECTOR_ICON_SIZE = 20
private const val SELECTOR_GAP = 8
