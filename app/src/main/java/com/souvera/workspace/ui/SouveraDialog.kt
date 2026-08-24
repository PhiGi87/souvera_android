/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Einheitlicher Souvera-Dialog für Bestätigungen und Auswahl-Nachfragen.
 * Alle Dialoge der App nutzen diese Komponente, damit Optik (runde Ecken,
 * Typografie, Button-Farben) überall identisch ist.
 */
@Composable
fun SouveraAlertDialog(
    onDismissRequest: () -> Unit,
    title: String?,
    text: String? = null,
    confirmText: String? = null,
    dismissText: String? = null,
    confirmDestructive: Boolean = false,
    onConfirm: (() -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val titleSlot: (@Composable () -> Unit)? = title?.let { t ->
        {
            Text(
                t,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
    val textSlot: (@Composable () -> Unit)? = if (text != null || content != null) {
        {
            Column {
                if (text != null) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (content != null) {
                    Spacer(Modifier.height(4.dp))
                    content()
                }
            }
        }
    } else {
        null
    }
    val confirmSlot: @Composable () -> Unit = if (onConfirm != null) {
        {
            TextButton(
                onClick = {
                    onDismissRequest()
                    onConfirm()
                }
            ) {
                Text(
                    confirmText.orEmpty(),
                    color = if (confirmDestructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    fontWeight = FontWeight.Medium
                )
            }
        }
    } else {
        {}
    }
    val dismissSlot: (@Composable () -> Unit)? = dismissText?.let { t ->
        {
            TextButton(onClick = onDismissRequest) {
                Text(t, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        title = titleSlot,
        text = textSlot,
        confirmButton = confirmSlot,
        dismissButton = dismissSlot
    )
}
