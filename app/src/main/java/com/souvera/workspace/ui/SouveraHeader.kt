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
import androidx.compose.foundation.layout.statusBarsPadding
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
            Color(0xFF062A63),
            Color(0xFF0A5CF5),
            Color(0xFF2E7BFF),
        )
    )

/** Inhaltsfarbe auf dem Header-Verlauf (Texte, Icons). */
val SouveraOnHeader = Color.White

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
