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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.owncloud.android.R
import com.souvera.workspace.mail.db.entity.MessageEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun MailLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun MailPlaceholder(text: String, icon: ImageVector? = null) {
    Box(Modifier.fillMaxSize().padding(PLACEHOLDER_PADDING.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(PLACEHOLDER_ICON_SIZE.dp).padding(bottom = PLACEHOLDER_GAP.dp)
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun MailAvatar(displayName: String?, address: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val palette = listOf(
        scheme.primaryContainer to scheme.onPrimaryContainer,
        scheme.secondaryContainer to scheme.onSecondaryContainer,
        scheme.tertiaryContainer to scheme.onTertiaryContainer,
        scheme.errorContainer to scheme.onErrorContainer,
        scheme.surfaceVariant to scheme.onSurfaceVariant
    )
    val index = ((address.hashCode() % palette.size) + palette.size) % palette.size
    val (background, foreground) = palette[index]
    val source = displayName?.trim().takeUnless { it.isNullOrEmpty() } ?: address
    val initial = (source.firstOrNull() ?: '?').uppercaseChar()
    Box(
        modifier = modifier.size(AVATAR_SIZE.dp).background(background, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(initial.toString(), color = foreground, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun MessageRow(message: MessageEntity, onClick: () -> Unit, onToggleFlag: () -> Unit) {
    val sender = message.fromDisplayName?.takeIf { it.isNotBlank() }
        ?: message.fromAddress.ifBlank { stringResource(R.string.mail_unknown_sender) }
    val subject = message.subject.ifBlank { stringResource(R.string.mail_no_subject) } +
        if (message.hasAttachments) "  📎" else ""
    val weight = if (message.isRead) FontWeight.Normal else FontWeight.Bold
    val subjectColor =
        if (message.isRead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val dateColor =
        if (message.isRead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
    val starTint =
        if (message.isFlagged) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outlineVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = ROW_PADDING.dp, vertical = ROW_PADDING_VERTICAL.dp)
    ) {
        MailAvatar(message.fromDisplayName, message.fromAddress)
        Column(Modifier.weight(1f).padding(horizontal = ROW_GAP.dp)) {
            Text(
                sender,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = weight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subject,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = weight,
                color = subjectColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatMailDate(message.dateSent),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = weight,
                color = dateColor
            )
            IconButton(onClick = onToggleFlag, modifier = Modifier.size(STAR_BUTTON_SIZE.dp)) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription =
                        stringResource(if (message.isFlagged) R.string.mail_unflag else R.string.mail_flag),
                    tint = starTint,
                    modifier = Modifier.size(STAR_ICON_SIZE.dp)
                )
            }
        }
    }
}

@Composable
fun transparentTextFieldColors(): TextFieldColors = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent
)

fun formatMailDate(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    val dateTime = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    return when {
        dateTime.toLocalDate() == today -> timeFormat.format(dateTime)
        dateTime.year == today.year -> dayMonthFormat.format(dateTime)
        else -> fullDateFormat.format(dateTime)
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dayMonthFormat = DateTimeFormatter.ofPattern("d. MMM")
private val fullDateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private const val AVATAR_SIZE = 40
private const val PLACEHOLDER_PADDING = 32
private const val PLACEHOLDER_ICON_SIZE = 56
private const val PLACEHOLDER_GAP = 8
private const val ROW_PADDING = 16
private const val ROW_PADDING_VERTICAL = 10
private const val ROW_GAP = 12
private const val STAR_BUTTON_SIZE = 32
private const val STAR_ICON_SIZE = 20
