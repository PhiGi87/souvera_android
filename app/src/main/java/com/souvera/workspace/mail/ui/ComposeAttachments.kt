/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.owncloud.android.R
import com.souvera.workspace.mail.model.OutgoingAttachment

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentEditRow(draft: ComposeDraftState) {
    if (draft.attachments.isEmpty()) return
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(CHIP_GAP.dp),
        contentPadding = PaddingValues(horizontal = ROW_PADDING.dp, vertical = ROW_PADDING_VERTICAL.dp)
    ) {
        itemsIndexed(draft.attachments) { index, attachment ->
            InputChip(
                selected = false,
                onClick = {
                    draft.attachments = draft.attachments.filterIndexed { i, _ -> i != index }
                },
                label = { Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.mail_remove_attachment),
                        modifier = Modifier.size(CHIP_ICON_SIZE.dp)
                    )
                }
            )
        }
    }
}

fun queryAttachment(context: Context, uri: Uri): OutgoingAttachment? {
    val mimeType = context.contentResolver.getType(uri) ?: DEFAULT_MIME
    return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
            val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
            OutgoingAttachment(uri.toString(), name ?: DEFAULT_NAME, size, mimeType)
        } else {
            null
        }
    }
}

private const val DEFAULT_MIME = "application/octet-stream"
private const val DEFAULT_NAME = "attachment"
private const val CHIP_GAP = 8
private const val ROW_PADDING = 16
private const val ROW_PADDING_VERTICAL = 4
private const val CHIP_ICON_SIZE = 18
