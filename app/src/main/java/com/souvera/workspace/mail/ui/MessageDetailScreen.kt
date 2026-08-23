/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.ui

import android.text.format.Formatter
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.owncloud.android.R
import com.souvera.workspace.mail.MailSettings
import com.souvera.workspace.mail.db.entity.MessageEntity
import com.souvera.workspace.mail.model.AttachmentMeta
import com.souvera.workspace.mail.model.MessageBody

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailScreen(viewModel: MailViewModel, message: MessageEntity) {
    val bodyState by viewModel.body.collectAsState()
    val isDeleting by viewModel.isDeleting.collectAsState()
    var flagged by rememberSaveable(message.emailId) { mutableStateOf(message.isFlagged) }
    var confirmDelete by rememberSaveable(message.emailId) { mutableStateOf(false) }
    var confirmSpam by rememberSaveable(message.emailId) { mutableStateOf(false) }
    val starTint =
        if (flagged) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = { viewModel.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            flagged = !flagged
                            viewModel.toggleFlagged(message, flagged)
                        }
                    ) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription =
                                stringResource(if (flagged) R.string.mail_unflag else R.string.mail_flag),
                            tint = starTint
                        )
                    }
                    if (isDeleting) {
                        Box(
                            Modifier.size(48.dp).padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else {
                        IconButton(onClick = { confirmSpam = true }) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = stringResource(R.string.mail_spam),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.mail_delete))
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    viewModel.navigate(
                        MailRoute.Compose(
                            to = message.fromAddress,
                            subject = replySubject(message.subject),
                            inReplyTo = message.messageId,
                            quoted = buildQuote(message, bodyState)
                        )
                    )
                },
                icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                text = { Text(stringResource(R.string.mail_reply)) }
            )
        }
    ) { padding ->
        DetailContent(
            message = message,
            bodyState = bodyState,
            onOpenAttachment = { index -> viewModel.openAttachment(message, index) },
            modifier = Modifier.fillMaxSize().padding(padding)
        )
    }

    if (confirmSpam) {
        AlertDialog(
            onDismissRequest = { confirmSpam = false },
            title = { Text(stringResource(R.string.mail_spam_title)) },
            text = { Text(stringResource(R.string.mail_spam_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSpam = false
                        viewModel.spamMessage(message)
                    }
                ) {
                    Text(stringResource(R.string.mail_spam), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSpam = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.mail_delete_title)) },
            text = { Text(stringResource(R.string.mail_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.deleteMessage(message)
                    }
                ) {
                    Text(stringResource(R.string.mail_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun DetailContent(
    message: MessageEntity,
    bodyState: MailUiState<MessageBody>,
    onOpenAttachment: (Int) -> Unit,
    modifier: Modifier
) {
    Column(modifier) {
        Text(
            message.subject.ifBlank { stringResource(R.string.mail_no_subject) },
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = CONTENT_PADDING.dp)
        )
        MessageHeader(message)
        HorizontalDivider()
        if (bodyState is MailUiState.Success && bodyState.data.attachments.isNotEmpty()) {
            AttachmentChips(bodyState.data.attachments, onOpenAttachment)
        }
        Box(Modifier.weight(1f)) { MessageBodyView(bodyState) }
    }
}

@Composable
private fun MessageHeader(message: MessageEntity) {
    val sender = message.fromDisplayName?.takeIf { it.isNotBlank() }
        ?: message.fromAddress.ifBlank { stringResource(R.string.mail_unknown_sender) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = CONTENT_PADDING.dp, vertical = HEADER_PADDING.dp)
    ) {
        MailAvatar(message.fromDisplayName, message.fromAddress)
        Column(Modifier.weight(1f).padding(start = HEADER_GAP.dp)) {
            Text(
                sender,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (message.fromAddress.isNotBlank()) {
                Text(
                    message.fromAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            formatMailDate(message.dateSent),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AttachmentChips(attachments: List<AttachmentMeta>, onOpen: (Int) -> Unit) {
    val context = LocalContext.current
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(ATTACHMENT_GAP.dp),
        contentPadding = PaddingValues(horizontal = CONTENT_PADDING.dp, vertical = ATTACHMENT_ROW_PADDING.dp)
    ) {
        itemsIndexed(attachments) { index, attachment ->
            AssistChip(
                onClick = { onOpen(index) },
                label = {
                    val size = Formatter.formatShortFileSize(context, attachment.sizeBytes)
                    Text("${attachment.name} ($size)", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            )
        }
    }
}

@Composable
private fun MessageBodyView(state: MailUiState<MessageBody>) {
    when (state) {
        is MailUiState.Loading -> MailLoading()

        is MailUiState.Error -> MailPlaceholder(state.message, Icons.Filled.Warning)

        is MailUiState.Success -> {
            val html = state.data.html
            if (!html.isNullOrBlank()) {
                HtmlBody(html)
            } else {
                SelectionContainer {
                    Text(
                        state.data.plainText.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(CONTENT_PADDING.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HtmlBody(html: String) {
    val context = LocalContext.current
    val allowByDefault = remember { MailSettings(context).loadRemoteImages }
    var loadRemote by remember { mutableStateOf(allowByDefault) }
    val hasRemote = remember(html) { html.contains("http://", true) || html.contains("https://", true) }

    Column(Modifier.fillMaxSize()) {
        if (!loadRemote && hasRemote) {
            RemoteContentBanner(onLoad = { loadRemote = true })
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                WebView(viewContext).apply {
                    settings.javaScriptEnabled = false
                    settings.allowFileAccess = false
                }
            },
            update = { web ->
                // Block ALL remote loads (images, tracking pixels, remote CSS) until the user opts in.
                web.settings.blockNetworkLoads = !loadRemote
                web.settings.blockNetworkImage = !loadRemote
                web.loadDataWithBaseURL(null, wrapHtml(html), "text/html", "utf-8", null)
            }
        )
    }
}

@Composable
private fun RemoteContentBanner(onLoad: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onLoad)
            .padding(horizontal = CONTENT_PADDING.dp, vertical = HEADER_PADDING.dp)
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stringResource(R.string.mail_blocked_remote_content),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(start = HEADER_GAP.dp)
        )
        Text(
            stringResource(R.string.mail_load_remote_content),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

private fun wrapHtml(html: String): String =
    "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<style>body{margin:16px;font-family:sans-serif;word-wrap:break-word}" +
        "img{max-width:100%;height:auto}</style></head><body>$html</body></html>"

private fun replySubject(subject: String): String =
    if (subject.startsWith(REPLY_PREFIX, ignoreCase = true)) subject else "$REPLY_PREFIX $subject"

/** Builds the "> "-quoted original for a reply, from the loaded body (plain text, HTML stripped). */
private fun buildQuote(message: MessageEntity, bodyState: MailUiState<MessageBody>): String {
    val original = (bodyState as? MailUiState.Success)?.data?.let { body ->
        body.plainText?.takeIf { it.isNotBlank() } ?: body.html?.replace(Regex("<[^>]+>"), " ")
    }.orEmpty().trim()
    val sender = message.fromDisplayName?.takeIf { it.isNotBlank() } ?: message.fromAddress
    val date = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(message.dateSent))
    val header = "Am $date schrieb $sender:"
    val quoted = original.lineSequence().joinToString("\n") { "> $it" }
    return "$header\n$quoted"
}

private const val REPLY_PREFIX = "Re:"
private const val CONTENT_PADDING = 16
private const val HEADER_PADDING = 12
private const val HEADER_GAP = 12
private const val ATTACHMENT_GAP = 8
private const val ATTACHMENT_ROW_PADDING = 4
