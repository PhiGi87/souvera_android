/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.status

import android.accounts.AccountManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.owncloud.android.R
import com.souvera.workspace.dav.SouveraSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Top-bar action to view and change the global Nextcloud user status (online/away/dnd/invisible).
 * Self-contained: resolves the single Souvera account and calls the user_status OCS API off the
 * main thread. Drop into any Compose top bar via `actions { StatusAction() }`.
 */
@Composable
fun StatusAction() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf<UserStatusType?>(null) }
    var open by remember { mutableStateOf(false) }

    fun api(): StatusApi? {
        val account = AccountManager.get(context)
            .getAccountsByType(context.getString(R.string.account_type)).firstOrNull() ?: return null
        return SouveraSyncManager(context).resolve(account)?.let { StatusApi(it) }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { current = runCatching { api()?.current() }.getOrNull() }
    }

    IconButton(onClick = { open = true }) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Icon(Icons.Filled.AccountCircle, contentDescription = stringResource(R.string.status_change))
            Box(
                Modifier.size(STATUS_DOT.dp).clip(CircleShape)
                    .background(Color.White)
                    .padding(STATUS_DOT_BORDER.dp)
            ) {
                Box(
                    Modifier.size(
                        STATUS_DOT_INNER.dp
                    ).clip(CircleShape).background((current ?: UserStatusType.INVISIBLE).dotColor)
                )
            }
        }
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        UserStatusType.entries.forEach { type ->
            DropdownMenuItem(
                leadingIcon = {
                    Box(Modifier.size(STATUS_MENU_DOT.dp).clip(CircleShape).background(type.dotColor))
                },
                text = { Text(statusLabel(context, type)) },
                onClick = {
                    open = false
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { runCatching { api()?.set(type) }.getOrNull() == true }
                        if (ok) current = type
                    }
                }
            )
        }
    }
}

private fun statusLabel(context: Context, type: UserStatusType): String = when (type) {
    UserStatusType.ONLINE -> context.getString(R.string.status_online)
    UserStatusType.AWAY -> context.getString(R.string.status_away)
    UserStatusType.DND -> context.getString(R.string.status_dnd)
    UserStatusType.INVISIBLE -> context.getString(R.string.status_invisible)
}

private const val STATUS_DOT = 14
private const val STATUS_DOT_BORDER = 2
private const val STATUS_DOT_INNER = 10
private const val STATUS_MENU_DOT = 12
