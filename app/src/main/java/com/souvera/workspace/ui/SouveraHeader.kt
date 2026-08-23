/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
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
    navigationBack: Boolean = false,
    searchQuery: String? = null,
    onSearchQueryChange: ((String) -> Unit)? = null,
    extraActions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(SOUVERA_HEADER_HEIGHT.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SouveraLogo(Modifier.padding(end = 8.dp), size = 30.dp)

        val pill: @Composable () -> Unit = {
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
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (searchQuery.isEmpty()) {
                            androidx.compose.material3.Text(
                                searchHint,
                                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                        androidx.compose.foundation.text.BasicTextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            singleLine = true,
                            textStyle = androidx.compose.material3.MaterialTheme.typography.bodyLarge.copy(
                                color = Color.White
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        androidx.compose.material3.IconButton(onClick = { onSearchQueryChange("") }) {
                            androidx.compose.material3.Icon(
                                Icons.Filled.Clear,
                                contentDescription = null
                            )
                        }
                    }
                } else {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        androidx.compose.material3.Text(
                            searchHint,
                            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
                com.souvera.workspace.status.StatusAction()
                extraActions()
            }
        }

        if (searchQuery != null && onSearchQueryChange != null) {
            androidx.compose.material3.Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = Color.White.copy(alpha = 0.16f),
                contentColor = Color.White,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
            ) {
                pill()
            }
        } else {
            androidx.compose.material3.Surface(
                onClick = onOpenSearch,
                shape = androidx.compose.foundation.shape.CircleShape,
                color = Color.White.copy(alpha = 0.16f),
                contentColor = Color.White,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
            ) {
                pill()
            }
        }
    }
}

/**
 * DIE einheitliche Header-Vorlage für alle Top-Level-Bereiche:
 * Verlauf + [SouveraHomeHeaderRow] + optionaler Slot unterhalb der Zeile.
 * Jeder Bereich nutzt AUSSCHLIESSLICH diese Komponente — dadurch sind
 * Abstände, Größen und Elemente überall identisch.
 */
@Composable
fun SouveraHomeHeader(
    onOpenDrawer: () -> Unit,
    onOpenSearch: () -> Unit,
    searchHint: String,
    modifier: Modifier = Modifier,
    navigationBack: Boolean = false,
    searchQuery: String? = null,
    onSearchQueryChange: ((String) -> Unit)? = null,
    extraActions: @Composable RowScope.() -> Unit = {},
) {
    SouveraHeader(modifier) {
        SouveraHomeHeaderRow(
            onOpenDrawer = onOpenDrawer,
            onOpenSearch = onOpenSearch,
            searchHint = searchHint,
            navigationBack = navigationBack,
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            extraActions = extraActions
        )
    }
}

/**
 * Deckende, theme-bewusste Hintergrundfarbe für Inhaltsflächen.
 * Verhindert, dass der Fenster-Verlauf durchscheint, und folgt dabei dem
 * Hell-/Dunkelmodus (statt hart weiß zu bleiben).
 */
@Composable
fun SouveraContentBackground(): Color {
    val bg = androidx.compose.material3.MaterialTheme.colorScheme.background
    if (bg.alpha > 0f) return bg.copy(alpha = 1f)
    return if (androidx.compose.foundation.isSystemInDarkTheme()) {
        Color(0xFF101014)
    } else {
        Color(0xFFFFFFFF)
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
