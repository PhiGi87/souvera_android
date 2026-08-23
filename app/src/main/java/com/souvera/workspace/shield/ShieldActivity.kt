/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.shield

import androidx.activity.enableEdgeToEdge
import android.accounts.AccountManager
import android.os.Bundle
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.updatePadding
import androidx.core.view.WindowInsetsCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.owncloud.android.R
import com.owncloud.android.ui.activity.DrawerActivity
import com.souvera.workspace.dav.SouveraSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Souvera Shield in der App: Spam-Quarantäne ansehen (filterbar nach
 * Empfänger), Nachricht vorschauen, freigeben und löschen. Nutzt die offene
 * souvera_shield-API mit dem App-Passwort des Kontos.
 */
class ShieldActivity : DrawerActivity() {

    private var api: ShieldApi? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        // Nahtloser Übergang: Falls ein Gerät Edge-to-Edge nicht umsetzt,
        // färbt die Systemleiste exakt in der obersten Verlaufsfarbe —
        // so wirkt der Verlauf immer bis hinter die Uhr durchgezogen.
        @Suppress("DEPRECATION")
        window.statusBarColor = 0xFF1E4666.toInt()

        setContentView(R.layout.activity_souvera_shield)
        installInsetHandling()
        setupToolbarShowOnlyMenuButtonAndTitle(getString(R.string.drawer_item_shield)) { openDrawer() }
        setupDrawer(R.id.nav_shield)
        findViewById<View>(R.id.appbar)?.visibility = View.GONE

        val account = AccountManager.get(this)
            .getAccountsByType(getString(R.string.account_type)).firstOrNull()
        val dav = account?.let { SouveraSyncManager(this).resolve(it) }
        api = dav?.let { ShieldApi(it) }

        val colorScheme = viewThemeUtils.getColorScheme(this)
        findViewById<androidx.compose.ui.platform.ComposeView>(R.id.shield_compose_view).setContent {
            MaterialTheme(colorScheme = colorScheme) {
                ShieldScreen(api)
            }
        }
    }

    @Composable
    private fun ShieldScreen(api: ShieldApi?) {
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        var loading by remember { mutableStateOf(true) }
        var refreshing by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var mails by remember { mutableStateOf<List<ShieldMail>>(emptyList()) }
        var selected by remember { mutableStateOf<ShieldMail?>(null) }
        var busy by remember { mutableStateOf(false) }
        var filterRecipient by remember { mutableStateOf<String?>(null) }

        fun refresh(silent: Boolean) {
            scope.launch {
                if (api == null) {
                    error = getString(R.string.shield_no_account)
                    loading = false
                    return@launch
                }
                if (silent) refreshing = true else loading = true
                error = null
                val result = runCatching { withContext(Dispatchers.IO) { api.listQuarantine() } }
                if (result.isSuccess) {
                    mails = result.getOrThrow()
                } else {
                    error = getString(R.string.shield_load_failed)
                }
                loading = false
                refreshing = false
            }
        }

        LaunchedEffect(Unit) { refresh(false) }

        val recipients = remember(mails) { mails.map { it.pmail }.distinct() }
        val visible = remember(mails, filterRecipient) {
            if (filterRecipient == null) mails else mails.filter { it.pmail == filterRecipient }
        }

        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                ShieldHeader(
                    total = mails.size,
                    visibleCount = visible.size,
                    refreshing = refreshing,
                    onRefresh = { refresh(true) }
                )
                FilterRow(
                    recipients = recipients,
                    selected = filterRecipient,
                    counts = mails.groupingBy { it.pmail }.eachCount(),
                    onSelect = { filterRecipient = it }
                )
                when {
                    loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    error != null -> Column(
                        Modifier.fillMaxWidth().weight(1f).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(error ?: "", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { refresh(false) }) {
                            Text(getString(R.string.shield_retry))
                        }
                    }
                    visible.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            getString(R.string.shield_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(visible, key = { it.id + it.pmail }) { mail ->
                            QuarantineRow(mail) { selected = mail }
                        }
                    }
                }
            }

            selected?.let { mail ->
                QuarantineDetail(
                    mail = mail,
                    api = api,
                    busy = busy,
                    onRelease = {
                        scope.launch {
                            busy = true
                            val ok = withContext(Dispatchers.IO) { api?.release(mail.id, mail.pmail) == true }
                            busy = false
                            if (ok) {
                                selected = null
                                snackbar.showSnackbar(getString(R.string.shield_released))
                                refresh(true)
                            } else {
                                snackbar.showSnackbar(getString(R.string.shield_action_failed))
                            }
                        }
                    },
                    onReleaseWhitelist = {
                        scope.launch {
                            busy = true
                            val sender = extractSenderEmail(mail.from)
                            val ok = sender != null && withContext(Dispatchers.IO) {
                                api?.releaseWhitelist(mail.id, mail.pmail, sender) == true
                            }
                            busy = false
                            if (ok) {
                                selected = null
                                snackbar.showSnackbar(getString(R.string.shield_released_whitelisted))
                                refresh(true)
                            } else {
                                snackbar.showSnackbar(getString(R.string.shield_action_failed))
                            }
                        }
                    },
                    onDelete = {
                        scope.launch {
                            busy = true
                            val ok = withContext(Dispatchers.IO) { api?.delete(mail.id, mail.pmail) == true }
                            busy = false
                            if (ok) {
                                selected = null
                                snackbar.showSnackbar(getString(R.string.shield_deleted))
                                refresh(true)
                            } else {
                                snackbar.showSnackbar(getString(R.string.shield_action_failed))
                            }
                        }
                    },
                    onDismiss = { selected = null }
                )
            }

            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).padding(16.dp)
            )
        }
    }

    @Composable
    private fun ShieldHeader(total: Int, visibleCount: Int, refreshing: Boolean, onRefresh: () -> Unit) {
        val gradient = com.souvera.workspace.ui.SouveraHeaderGradient
        Column(
            Modifier
                .fillMaxWidth()
                .background(gradient)
                .statusBarsPadding()
                .padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .background(Color(0x33FFFFFF), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(R.drawable.ic_souvera_shield),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        getString(R.string.drawer_item_shield),
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (visibleCount != total) {
                            getString(R.string.shield_header_counts_filtered, visibleCount, total)
                        } else {
                            getString(R.string.shield_header_counts, total)
                        },
                        color = Color(0xFFB9C8DC),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (refreshing) {
                    CircularProgressIndicator(
                        Modifier.size(22.dp).padding(4.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = getString(R.string.shield_refresh),
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun FilterRow(
        recipients: List<String>,
        selected: String?,
        counts: Map<String, Int>,
        onSelect: (String?) -> Unit
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text(getString(R.string.shield_filter_all, counts.values.sum())) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
            recipients.forEach { recipient ->
                FilterChip(
                    selected = selected == recipient,
                    onClick = { onSelect(if (selected == recipient) null else recipient) },
                    label = { Text("$recipient (${counts[recipient] ?: 0})") }
                )
            }
        }
    }

    @Composable
    private fun QuarantineRow(mail: ShieldMail, onClick: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    mail.from.trim().take(1).uppercase(Locale.getDefault()).ifBlank { "?" },
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    mail.from.ifBlank { getString(R.string.shield_unknown_sender) },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    mail.subject.ifBlank { getString(R.string.shield_no_subject) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (mail.time > 0) {
                            SimpleDateFormat("dd.MM. HH:mm", Locale.getDefault()).format(Date(mail.time * 1000))
                        } else {
                            ""
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (mail.pmail.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            getString(R.string.shield_recipient, mail.pmail),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                getString(R.string.shield_spam_score, "%.1f".format(Locale.US, mail.spamlevel)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )
        }
    }

    @Composable
    private fun QuarantineDetail(
        mail: ShieldMail,
        api: ShieldApi?,
        busy: Boolean,
        onRelease: () -> Unit,
        onReleaseWhitelist: () -> Unit,
        onDelete: () -> Unit,
        onDismiss: () -> Unit
    ) {
        var body by remember { mutableStateOf<String?>(null) }
        var viewError by remember { mutableStateOf(false) }
        LaunchedEffect(mail.id) {
            body = withContext(Dispatchers.IO) { api?.viewMessage(mail.id, mail.pmail) }
            viewError = body == null
        }
        val gradient = Brush.verticalGradient(
            listOf(Color(0xFF0B1F33), Color(0xFF123253), Color(0xFF0B1622))
        )
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(Modifier.fillMaxWidth().background(gradient).padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .background(Color(0x33FFFFFF), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            mail.from.trim().take(1).uppercase(Locale.getDefault()).ifBlank { "?" },
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            mail.from.ifBlank { getString(R.string.shield_unknown_sender) },
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (mail.pmail.isNotBlank()) {
                            Text(
                                getString(R.string.shield_recipient, mail.pmail),
                                color = Color(0xFFB9C8DC),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Text(
                        getString(R.string.shield_spam_score, "%.1f".format(Locale.US, mail.spamlevel)),
                        color = Color(0xFFFF9E9E),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    mail.subject.ifBlank { getString(R.string.shield_no_subject) },
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 14.dp)
                )
                if (mail.time > 0) {
                    Text(
                        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(mail.time * 1000)),
                        color = Color(0xFFB9C8DC),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
                when {
                    body == null && !viewError -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    viewError -> Text(
                        getString(R.string.shield_view_failed),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    else -> Text(
                        body.orEmpty(),
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDelete,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(getString(R.string.shield_delete))
                }
                OutlinedButton(
                    onClick = onReleaseWhitelist,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(getString(R.string.shield_release_whitelist))
                }
            }
            Button(
                onClick = onRelease,
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF0A5CF5)
                )
            ) {
                Text(getString(R.string.shield_release))
            }
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 8.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(getString(R.string.shield_close))
            }
        }
    }
    /** Zieht die E-Mail-Adresse aus "Name <mail@example.com>" bzw. "mail@example.com". */
    private fun extractSenderEmail(from: String): String? {
        val match = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+").find(from) ?: return null
        return match.value
    }

    private fun installInsetHandling() {
        val root = findViewById<View>(R.id.drawer_layout)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bottomInset = maxOf(
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            )
            view.updatePadding(bottom = bottomInset)
            WindowInsetsCompat.Builder(insets)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.NONE)
                .build()
        }
    }
}
