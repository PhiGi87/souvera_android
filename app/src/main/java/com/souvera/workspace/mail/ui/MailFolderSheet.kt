/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.owncloud.android.R
import com.souvera.workspace.mail.db.entity.MailboxEntity
import com.souvera.workspace.mail.db.entity.MailboxKind
import com.souvera.workspace.mail.db.entity.NamespaceType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailFolderSheet(
    state: MailUiState<List<MailboxEntity>>,
    currentId: String?,
    personalLabel: String,
    onSelect: (MailboxEntity) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.mail_folders),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        when (state) {
            is MailUiState.Loading ->
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            is MailUiState.Error ->
                Text(state.message, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            is MailUiState.Success -> {
                val personalLabelResolved = personalLabel.ifBlank { stringResource(R.string.mail_folder_inbox) }
                val sharedPrefix = stringResource(R.string.mail_shared_prefix)
                val delegatedPrefix = stringResource(R.string.mail_shared_prefix_delegated)
                val groups = groupByMailbox(state.data, personalLabelResolved, sharedPrefix, delegatedPrefix)
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    groups.forEach { group ->
                        item(key = "hdr_${group.label}") {
                            MailboxGroupHeader(group.label, group.totalUnread)
                        }
                        items(group.folders, key = { it.id }) {
                            FolderRow(it, it.id == currentId, onSelect)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MailboxGroupHeader(label: String, unread: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        if (unread > 0) {
            Spacer(Modifier.width(8.dp))
            Text("$unread", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun FolderRow(mailbox: MailboxEntity, selected: Boolean, onSelect: (MailboxEntity) -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(bg)
            .clickable { onSelect(mailbox) }
            .padding(horizontal = 20.dp, vertical = 11.dp)
    ) {
        Icon(mailbox.kind.folderIcon(), contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                   else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp))
        Text(mailboxDisplayName(mailbox), style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(start = 14.dp))
        if (mailbox.unreadCount > 0) {
            Text(mailbox.unreadCount.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

fun MailboxKind.folderIcon(): ImageVector = when (this) {
    MailboxKind.INBOX -> Icons.Filled.Email
    MailboxKind.SENT -> Icons.AutoMirrored.Filled.Send
    MailboxKind.DRAFTS -> Icons.Filled.Edit
    MailboxKind.TRASH -> Icons.Filled.Delete
    MailboxKind.JUNK -> Icons.Filled.Warning
    MailboxKind.REGULAR -> Icons.Filled.MailOutline
}

@Composable
fun mailboxDisplayName(mailbox: MailboxEntity): String = when (mailbox.kind) {
    MailboxKind.INBOX -> stringResource(R.string.mail_folder_inbox)
    MailboxKind.SENT -> stringResource(R.string.mail_folder_sent)
    MailboxKind.DRAFTS -> stringResource(R.string.mail_folder_drafts)
    MailboxKind.TRASH -> stringResource(R.string.mail_folder_trash)
    MailboxKind.JUNK -> stringResource(R.string.mail_folder_junk)
    MailboxKind.REGULAR -> mailbox.name
}

private data class MailboxGroup(val label: String, val folders: List<MailboxEntity>, val totalUnread: Int)

private fun groupByMailbox(list: List<MailboxEntity>, personalLabel: String, sharedPrefix: String, delegatedPrefix: String): List<MailboxGroup> {
    val deduped = list.distinctBy { it.path }
    val sorted = compareBy<MailboxEntity>({ it.kind.ordinal }, { it.name.lowercase() })
    val groups = mutableListOf<MailboxGroup>()

    val personal = deduped.filter { it.namespaceType == NamespaceType.PERSONAL }
    val rest = deduped.filter { it.namespaceType != NamespaceType.PERSONAL }

    if (personal.isNotEmpty()) {
        groups.add(MailboxGroup(personalLabel, personal.sortedWith(sorted), personal.sumOf { it.unreadCount }))
    }

    val byOwner = rest.groupBy { it.ownerIdentity ?: it.path.substringBefore('/') }
    byOwner.toSortedMap().forEach { (owner, folders) ->
        val sortedF = folders.sortedWith(sorted)
        val prefix = if (folders.firstOrNull()?.namespaceType == NamespaceType.OTHER_USERS) delegatedPrefix else sharedPrefix
        groups.add(MailboxGroup("$prefix $owner", sortedF, sortedF.sumOf { it.unreadCount }))
    }
    return groups
}
