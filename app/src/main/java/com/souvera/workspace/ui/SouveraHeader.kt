/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Souvera-Markenverlauf für App-Header: tiefes Royalblau läuft über das
 * lebhafte Souvera-Blau in ein helleres Blau aus. Wird appweit für alle
 * Kopfbereiche (Mail, Talk, Shield, Notizen, Suche) verwendet.
 */
val SouveraHeaderGradient: Brush
    @Composable
    @ReadOnlyComposable
    get() = Brush.verticalGradient(
        listOf(
            Color(0xFF1E4666),
            Color(0xFF2F5F8B),
            Color(0xFF4D93D4),
        )
    )

/** Inhaltsfarbe auf dem Header-Verlauf (Texte, Icons). */
val SouveraOnHeader = Color.White

/** Souvera-Icon (Originalfarben, identisch mit dem App-Icon-Mark). */
@Composable
fun SouveraLogo(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 28.dp) {
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(com.owncloud.android.R.drawable.souvera_icon),
        contentDescription = "Souvera",
        modifier = modifier.size(size)
    )
}

/** Souvera-Wortmarke (weiße Schrift auf dem Blauverlauf) für Header-Flächen. */
@Composable
fun SouveraWordmark(modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 26.dp) {
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(com.owncloud.android.R.drawable.souvera_wordmark),
        contentDescription = "Souvera",
        modifier = modifier.height(height)
    )
}

/**
 * Einheitliche Kopfzeile für die Top-Level-Bereiche (Mail, Talk):
 * S-Icon | Menü | Such-Pille | Profil-Status | Einstellungs-Zahnrad.
 * Wird in den Souvera-Gradient-Container ([SouveraHeader]) eingesetzt.
 */
@Composable
fun SouveraHomeHeaderRow(
    onOpenDrawer: () -> Unit,
    onOpenSearch: () -> Unit,
    searchHint: String,
    modifier: Modifier = Modifier,
    onOpenSettings: (() -> Unit)? = null,
    navigationBack: Boolean = false,
    searchQuery: String? = null,
    onSearchQueryChange: ((String) -> Unit)? = null,
    extraActions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(SOUVERA_HEADER_HEIGHT.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SouveraLogo(Modifier.padding(end = 8.dp), size = 30.dp)
        androidx.compose.material3.Surface(
            onClick = if (searchQuery != null) ({}) else onOpenSearch,
            shape = androidx.compose.foundation.shape.CircleShape,
            color = Color.White.copy(alpha = 0.16f),
            contentColor = Color.White,
            modifier = Modifier.weight(1f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 4.dp, end = 12.dp)
            ) {
                androidx.compose.material3.IconButton(onClick = onOpenDrawer) {
                    androidx.compose.material3.Icon(
                        if (navigationBack) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Menu,
                        contentDescription = null
                    )
                }
                if (searchQuery != null && onSearchQueryChange != null) {
                    androidx.compose.material3.TextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        placeholder = {
                            androidx.compose.material3.Text(
                                searchHint,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        },
                        singleLine = true,
                        textStyle = androidx.compose.material3.MaterialTheme.typography.bodyLarge.copy(
                            color = Color.White
                        ),
                        colors = androidx.compose.material3.TextFieldDefaults.colors(
                            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            cursorColor = Color.White,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    if (searchQuery.isNotEmpty()) {
                        androidx.compose.material3.IconButton(onClick = { onSearchQueryChange("") }) {
                            androidx.compose.material3.Icon(
                                Icons.Filled.Clear,
                                contentDescription = null
                            )
                        }
                    }
                } else {
                    androidx.compose.material3.Text(
                        searchHint,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.weight(1f)
                    )
                }
                com.souvera.workspace.status.StatusAction()
                extraActions()
                if (onOpenSettings != null) {
                    androidx.compose.material3.IconButton(onClick = onOpenSettings) {
                        androidx.compose.material3.Icon(
                            Icons.Filled.Settings,
                            contentDescription = null
                        )
                    }
                }
            }
        }
    }
}

/** Standardhöhe der Header-Leiste. */
const val SOUVERA_HEADER_HEIGHT = 64

/**
 * Ersatz für die Material-3-[TopAppBar] mit Souvera-Blaugradient:
 * weißer Inhalt, Statusbar-Inset inklusive.
 */
@Composable
fun SouveraTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    logo: Boolean = false,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(SouveraHeaderGradient)
            .statusBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(SOUVERA_HEADER_HEIGHT.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompositionLocalProvider(LocalContentColor provides SouveraOnHeader) {
                navigationIcon()
                if (logo) {
                    SouveraLogo(Modifier.padding(start = 2.dp, end = 10.dp))
                }
                Box(Modifier.weight(1f)) { title() }
                actions()
            }
        }
    }
}

/**
 * Flexibler Gradient-Container für individuelle Kopfbereiche
 * (Mail-Home, Suche, Shield-Übersicht).
 */
@Composable
fun SouveraHeader(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(SouveraHeaderGradient)
            .statusBarsPadding()
    ) {
        CompositionLocalProvider(LocalContentColor provides SouveraOnHeader) {
            content()
        }
    }
}
