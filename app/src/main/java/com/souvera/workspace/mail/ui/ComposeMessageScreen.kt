/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditorDefaults
import com.owncloud.android.R
import com.souvera.workspace.mail.MailSettings
import com.souvera.workspace.mail.model.OutgoingMessage

@Composable
fun ComposeMessageScreen(viewModel: MailViewModel, route: MailRoute.Compose) {
    val sendState by viewModel.sendState.collectAsState()
    val fromAddress by viewModel.fromAddress.collectAsState()
    val fromIdentities by viewModel.fromIdentities.collectAsState()
    val selectedFrom by viewModel.selectedFrom.collectAsState()
    val context = LocalContext.current
    val draft = rememberSaveable(saver = ComposeDraftState.Saver) {
        ComposeDraftState(route.to, route.subject).apply {
            inReplyTo = route.inReplyTo
            val signature = MailSettings(context).signature
            body = buildString {
                if (signature.isNotBlank()) append("\n\n-- \n").append(signature)
                if (route.quoted.isNotBlank()) append("\n\n").append(route.quoted)
            }
        }
    }
    val richState = rememberRichTextState()
    val recipientRequired = stringResource(R.string.mail_recipient_required)

    LaunchedEffect(richState) {
        if (draft.body.isNotBlank()) richState.setMarkdown(draft.body)
        snapshotFlow { richState.annotatedString }.collect {
            draft.body = richState.toMarkdown()
            draft.bodyHtml = richState.toHtml()
        }
    }

    HandleSendState(viewModel)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = com.souvera.workspace.ui.SouveraContentBackground(),
        topBar = {
            ComposeTopBar(
                sending = sendState is SendState.Sending,
                onClose = { viewModel.back() },
                onSend = { sendDraft(viewModel, context, draft, recipientRequired) }
            )
        }
    ) { padding ->
        ComposeScreenContent(
            draft = draft,
            fromIdentities = fromIdentities,
            selectedFrom = selectedFrom,
            onSelectFrom = { viewModel.selectFromIdentity(it) },
            sending = sendState is SendState.Sending,
            richState = richState,
            modifier = Modifier.fillMaxSize().padding(padding)
        )
    }
}

@Composable
private fun HandleSendState(viewModel: MailViewModel) {
    val sendState by viewModel.sendState.collectAsState()
    val context = LocalContext.current
    val sentMessage = stringResource(R.string.mail_sent)
    LaunchedEffect(sendState) {
        when (val current = sendState) {
            is SendState.Sent -> {
                Toast.makeText(context, sentMessage, Toast.LENGTH_SHORT).show()
                viewModel.consumeSendState()
                viewModel.back()
            }

            is SendState.Error -> {
                Toast.makeText(context, current.message, Toast.LENGTH_LONG).show()
                viewModel.consumeSendState()
            }

            else -> Unit
        }
    }
}

@Composable
private fun ComposeScreenContent(
    draft: ComposeDraftState,
    fromIdentities: List<String>,
    selectedFrom: String,
    onSelectFrom: (String) -> Unit,
    sending: Boolean,
    richState: RichTextState,
    modifier: Modifier
) {
    val context = LocalContext.current
    val attachmentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        draft.attachments = draft.attachments + uris.mapNotNull { queryAttachment(context, it) }
    }
    Column(modifier) {
        if (sending) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            ComposeForm(draft, fromIdentities, selectedFrom, onSelectFrom, richState)
        }
        AttachmentEditRow(draft)
        HorizontalDivider()
        FormattingBar(
            state = richState,
            onAttach = { attachmentPicker.launch(arrayOf("*/*")) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposeTopBar(sending: Boolean, onClose: () -> Unit, onSend: () -> Unit) {
    com.souvera.workspace.ui.SouveraTopBar(
        title = { Text(stringResource(R.string.mail_compose_title)) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = null)
            }
        },
        actions = {
            IconButton(onClick = onSend, enabled = !sending) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.mail_send),
                    tint = androidx.compose.ui.graphics.Color.White
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposeForm(draft: ComposeDraftState, identities: List<String>, selected: String, onSelect: (String) -> Unit, richState: RichTextState) {
    if (identities.isNotEmpty()) {
        FromRow(identities, selected, onSelect)
        HorizontalDivider()
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        RecipientField(
            value = draft.to,
            onValueChange = { draft.to = it },
            prefixText = stringResource(R.string.mail_recipient_hint),
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = { draft.showCcBcc = !draft.showCcBcc }) {
            Icon(
                if (draft.showCcBcc) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.mail_show_cc_bcc)
            )
        }
    }
    HorizontalDivider()
    if (draft.showCcBcc) {
        RecipientField(draft.cc, { draft.cc = it }, stringResource(R.string.mail_cc_hint))
        HorizontalDivider()
        RecipientField(draft.bcc, { draft.bcc = it }, stringResource(R.string.mail_bcc_hint))
        HorizontalDivider()
    }
    MailTextField(draft.subject, { draft.subject = it }, stringResource(R.string.mail_subject_hint))
    HorizontalDivider()
    RichTextEditor(
        state = richState,
        placeholder = { Text(stringResource(R.string.mail_body_hint)) },
        colors = RichTextEditorDefaults.richTextEditorColors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = BODY_MIN_HEIGHT.dp)
    )
}

@Composable
private fun FromRow(identities: List<String>, selected: String, onSelect: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = FIELD_PADDING.dp, vertical = FROM_PADDING.dp)
    ) {
        Text(stringResource(R.string.mail_from_label),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = PREFIX_GAP.dp))
        if (identities.size <= 1) {
            Text(selected.ifBlank { identities.firstOrNull().orEmpty() },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { expanded = true }
                    .padding(end = 8.dp)
            ) {
                Text(selected, style = MaterialTheme.typography.bodyLarge)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    identities.forEach { identity ->
                        DropdownMenuItem(text = { Text(identity) }, onClick = {
                            onSelect(identity)
                            expanded = false
                        }, leadingIcon = { if (identity == selected) Icon(
                            androidx.compose.material.icons.Icons.Filled.Check, contentDescription = null) })
                    }
                }
            }
        }
    }
}

@Composable
private fun MailTextField(
    value: String,
    onValueChange: (String) -> Unit,
    prefixText: String,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
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
        modifier = modifier
    )
}

private fun sendDraft(viewModel: MailViewModel, context: Context, draft: ComposeDraftState, recipientRequired: String) {
    val recipients = parseRecipients(draft.to)
    if (recipients.isEmpty()) {
        Toast.makeText(context, recipientRequired, Toast.LENGTH_SHORT).show()
        return
    }
    viewModel.send(
        OutgoingMessage(
            to = recipients,
            cc = parseRecipients(draft.cc),
            bcc = parseRecipients(draft.bcc),
            subject = draft.subject,
            body = draft.body,
            bodyHtml = draft.bodyHtml,
            attachments = draft.attachments,
            inReplyTo = draft.inReplyTo
        )
    )
}

private fun parseRecipients(value: String): List<String> =
    value.split(',', ';').map { it.trim() }.filter { it.isNotBlank() }

private const val FIELD_PADDING = 16
private const val FROM_PADDING = 12
private const val PREFIX_GAP = 8
private const val BODY_MIN_HEIGHT = 180
