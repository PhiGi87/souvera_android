/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.ui

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.owncloud.android.R
import com.souvera.workspace.link.call.CallActivity
import com.souvera.workspace.link.net.LinkChatMessage

/** WhatsApp-style bubble layout in Souvera blue; adapts to light/dark. */
private class ChatColors(dark: Boolean) {
    val background = if (dark) Color(0xFF0B1622) else Color(0xFFEAF1FB)
    val mine = if (dark) Color(0xFF14385F) else Color(0xFFCCE4FF)
    val other = if (dark) Color(0xFF1B2733) else Color(0xFFFFFFFF)
    val text = if (dark) Color(0xFFE7EDF3) else Color(0xFF0B1F33)
    val time = if (dark) Color(0xFF8AA0B3) else Color(0xFF5A7184)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: LinkViewModel, route: LinkRoute.Chat) {
    val context = LocalContext.current
    val baseUrl = viewModel.baseUrl
    
    val state by viewModel.messages.collectAsState()
    val readUpTo by viewModel.readUpTo.collectAsState()
    val peerStatus by viewModel.peerStatus.collectAsState()
    val failedMessages by viewModel.failedMessages.collectAsState()
    val retryInFlight by viewModel.retryInFlight.collectAsState()
    var input by remember { mutableStateOf("") }
    var emojiOpen by remember { mutableStateOf(false) }
    var callDialogOpen by remember { mutableStateOf(false) }
    var modalImage by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var replyQuote by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val messages = (state as? LinkUiState.Success)?.data.orEmpty().filter { it.systemMessage.isEmpty() }
    val me = viewModel.currentUserId
    val colors = ChatColors(isSystemInDarkTheme())

    val conversations by viewModel.conversations.collectAsState()
    val uploading by viewModel.uploading.collectAsState()
    val hasActiveCall = (conversations as? LinkUiState.Success)?.data
        ?.firstOrNull { it.token == route.token }?.hasCall == true

    val callPulse = rememberInfiniteTransition(label = "callPulse")
    val pulseScale by callPulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(tween(550), RepeatMode.Reverse),
        label = "pulseScale"
    )

    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.sendAttachment(it) }
    }

    val items = buildChatItems(messages, me, readUpTo, failedMessages, route.token)

    // reverseLayout verankert die Liste am BOTTOM: nachgeladene Bilder/
    // Anhaenge wachsen oberhalb des Ankers und verschieben die neueste
    // Nachricht nicht mehr aus dem Sichtfeld (Ursache des "Hochhuepfens").
    // Index 0 = neueste Nachricht.
    val userScrolled = remember(route.token) { mutableStateOf(false) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) userScrolled.value = true
    }
    LaunchedEffect(listState.firstVisibleItemIndex) {
        // Wieder ganz unten angekommen -> neuen Nachrichten wieder folgen.
        if (listState.firstVisibleItemIndex == 0) userScrolled.value = false
    }
    LaunchedEffect(items.size) {
        if (!userScrolled.value && items.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(route.token) {
        while (true) {
            viewModel.loadConversations()
            kotlinx.coroutines.delay(CALL_POLL_MS)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            com.souvera.workspace.ui.SouveraTopBar(
                title = {
                    val peerId = viewModel.chatPeerId.collectAsState().value
                    Row(
                        Modifier.clickable {
                            val id = peerId ?: route.title
                            viewModel.showUserProfile(id)
                        }
                    ) {
                        ChatTitle(route.title, peerStatus, peerId, viewModel)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { callDialogOpen = true }) {
                        Icon(
                            Icons.Filled.Phone,
                            contentDescription = stringResource(R.string.link_call),
                            tint = if (hasActiveCall) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                            modifier = Modifier.scale(if (hasActiveCall) pulseScale else 1f)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(colors.background)) {
            if (hasActiveCall) {
                IncomingCallBanner(onJoin = { callDialogOpen = true })
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = LIST_V.dp),
                reverseLayout = true
            ) {
                items(items.asReversed(), key = { it.key }) { item ->
                    when (item) {
                        is ChatItem.Separator -> DateSeparator(item.label, colors)
                        is ChatItem.Failed -> FailedMessageBubble(
                            text = item.text,
                            colors = colors,
                            retrying = item.localId in retryInFlight,
                            onRetry = { viewModel.retryMessage(item.localId) }
                        )
                        is ChatItem.Message -> MessageBubble(
                            message = item.message,
                            mine = item.mine,
                            grouped = item.grouped,
                            read = item.read,
                            colors = colors,
                            viewModel = viewModel,
                            onOpenImage = { img -> modalImage = img },
                            onCopy = { text ->
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("chat", text))
                            },
                            onReply = { quote -> replyQuote = quote }
                        )
                    }
                }
            }
            if (uploading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = INPUT_H.dp, vertical = INPUT_V.dp)
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        strokeWidth = UPLOAD_STROKE.dp,
                        modifier = Modifier.size(UPLOAD_SPINNER.dp)
                    )
                    Text(
                        stringResource(R.string.link_uploading_attachment),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.time,
                        modifier = Modifier.padding(start = INPUT_H.dp)
                    )
                }
            }
            if (emojiOpen) EmojiPicker { input += it }
            if (replyQuote != null) {
                ReplyQuoteBar(replyQuote!!, colors) { replyQuote = null }
            }
            InputBar(
                input = input,
                colors = colors,
                onInput = { input = it },
                onEmoji = { emojiOpen = !emojiOpen },
                onAttach = { attachmentPicker.launch(arrayOf("*/*")) },
                onSend = {
                    viewModel.send(input)
                    input = ""
                    emojiOpen = false
                }
            )
        }
    }

    modalImage?.let { image ->
        var zoomScale by remember { mutableFloatStateOf(1f) }
        val transformState = rememberTransformableState { zoomChange, _, _ ->
            zoomScale = (zoomScale * zoomChange).coerceIn(1f, 5f)
        }
        androidx.compose.ui.window.Dialog(onDismissRequest = { modalImage = null }) {
            Box(
                Modifier.fillMaxSize().background(Color(0xE6000000)).clickable { modalImage = null },
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(IMAGE_MODAL_PAD.dp)
                        .transformable(transformState)
                        .graphicsLayer(scaleX = zoomScale, scaleY = zoomScale)
                )
            }
        }
    }

    if (callDialogOpen) {
        CallTypeDialog(
            onDismiss = { callDialogOpen = false },
            onPick = { withVideo ->
                callDialogOpen = false
                startCall(context, route, withVideo)
            }
        )
    }

    val profile by viewModel.profilePeer.collectAsState()
    profile?.let { p ->
        ModalBottomSheet(onDismissRequest = { viewModel.closeUserProfile() }) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val avatarBytes by androidx.compose.runtime.produceState<ByteArray?>(null, p.userId) {
                    value = viewModel.loadAvatar(p.userId, 120)
                }
                val avatarBitmap = avatarBytes?.toImageBitmap()
                if (avatarBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = avatarBitmap,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.size(96.dp).clip(CircleShape)
                    )
                } else {
                    val color = MaterialTheme.colorScheme.primary
                    Box(
                        Modifier.size(96.dp).clip(CircleShape).background(color),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(p.displayName.firstOrNull()?.uppercase() ?: "?",
                            style = MaterialTheme.typography.headlineMedium, color = Color.White)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(p.displayName, style = MaterialTheme.typography.headlineSmall)
                if (p.email.isNotBlank()) {
                    Text(p.email, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (p.userId.isNotBlank()) {
                    Text("@${p.userId}", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (p.phone.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("📞 ", style = MaterialTheme.typography.bodyMedium)
                        Text(p.phone, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ChatTitle(
    title: String,
    peer: com.souvera.workspace.status.PeerStatus?,
    peerId: String?,
    viewModel: LinkViewModel
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Peer avatar (Nextcloud profile picture via avatar/{peerId}).
        val bytes by androidx.compose.runtime.produceState<ByteArray?>(null, peerId) {
            value = peerId?.let { viewModel.loadAvatar(it, 40) }
        }
        val bitmap = bytes?.toImageBitmap()
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(40.dp).clip(CircleShape)
            )
        } else {
            val color = AVATAR_PALETTE[(title.hashCode().mod(AVATAR_PALETTE.size))]
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(color),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    title.trim().firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title)
            if (peer != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(STATUS_DOT.dp).clip(CircleShape).background(peer.status.dotColor))
                    Text(
                        lastActiveLabel(peer),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = STATUS_DOT_GAP.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun lastActiveLabel(peer: com.souvera.workspace.status.PeerStatus): String = when (peer.status) {
    com.souvera.workspace.status.UserStatusType.ONLINE -> stringResource(R.string.link_online)
    com.souvera.workspace.status.UserStatusType.AWAY -> stringResource(R.string.link_away)
    com.souvera.workspace.status.UserStatusType.DND -> stringResource(R.string.link_dnd)
    else -> {
        if (peer.lastActivity <= 0) {
            ""
        } else {
            val diff = System.currentTimeMillis() / 1000 - peer.lastActivity
            when {
                diff < 60 -> stringResource(R.string.link_last_active_now)
                diff < 3600 -> pluralStringResource(R.plurals.link_last_active_min, (diff / 60).toInt(), diff / 60)
                diff < 86400 -> pluralStringResource(R.plurals.link_last_active_hour, (diff / 3600).toInt(), diff / 3600)
                else -> pluralStringResource(R.plurals.link_last_active_day, (diff / 86400).toInt(), diff / 86400)
            }
        }
    }
}

@Composable
private fun IncomingCallBanner(onJoin: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primary) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onJoin).padding(BANNER_PAD.dp)
        ) {
            Icon(
                painterResource(R.drawable.ic_phone),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary
            )
            Text(
                stringResource(R.string.link_call_ongoing),
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(start = BANNER_PAD.dp)
            )
            Text(
                stringResource(R.string.link_call_join),
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
private fun CallTypeDialog(onDismiss: () -> Unit, onPick: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.link_call_start_title)) },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        text = {
            Column {
                CallTypeRow(R.drawable.ic_phone, stringResource(R.string.link_start_audio_call)) { onPick(false) }
                CallTypeRow(R.drawable.ic_video_camera, stringResource(R.string.link_start_video_call)) { onPick(true) }
            }
        }
    )
}

@Composable
private fun CallTypeRow(iconRes: Int, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = CALL_ROW_V.dp)
    ) {
        Icon(
            painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = CALL_ROW_GAP.dp)
        )
    }
}

private fun startCall(context: android.content.Context, route: LinkRoute.Chat, withVideo: Boolean) {
    context.startActivity(
        Intent(context, CallActivity::class.java)
            .putExtra(CallActivity.EXTRA_TOKEN, route.token)
            .putExtra(CallActivity.EXTRA_TITLE, route.title)
            .putExtra(CallActivity.EXTRA_VIDEO, withVideo)
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: LinkChatMessage,
    mine: Boolean,
    grouped: Boolean,
    read: Boolean,
    colors: ChatColors,
    viewModel: LinkViewModel,
    onOpenImage: (androidx.compose.ui.graphics.ImageBitmap) -> Unit,
    onCopy: (String) -> Unit,
    onReply: (String) -> Unit,
) {
    var contextMenuOpen by remember { mutableStateOf(false) }
    val fileName = message.fileName()
    val fileId = message.fileId()
    val isImage = message.isImageFile() && fileId != null
    val shape = if (mine) {
        RoundedCornerShape(BUBBLE_CORNER.dp, TAIL_CORNER.dp, BUBBLE_CORNER.dp, BUBBLE_CORNER.dp)
    } else {
        RoundedCornerShape(TAIL_CORNER.dp, BUBBLE_CORNER.dp, BUBBLE_CORNER.dp, BUBBLE_CORNER.dp)
    }
    val onClick: (() -> Unit)? = when {
        isImage -> null
        fileName != null -> ({ viewModel.openSharedFile(message) })
        else -> null
    }
    var bubbleModifier = Modifier.widthIn(max = BUBBLE_MAX.dp)
        .clip(shape)
        .background(if (mine) colors.mine else colors.other)
        .combinedClickable(
            onClick = onClick ?: {},
            onLongClick = { contextMenuOpen = true },
        )
    bubbleModifier = bubbleModifier.padding(horizontal = BUBBLE_PAD_H.dp, vertical = BUBBLE_PAD_V.dp)

    val verticalPadding = if (grouped) 1 else BUBBLE_V
    val dismissState = rememberSwipeToDismissBoxState()

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.StartToEnd ||
            dismissState.currentValue == SwipeToDismissBoxValue.EndToStart
        ) {
            onReply(message.actorDisplayName ?: message.actorId ?: "")
            dismissState.reset()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = { SwipeReplyHint(dismissState.dismissDirection, colors) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = BUBBLE_H.dp, vertical = verticalPadding.dp)
    ) {
        Row(
            Modifier.fillMaxSize(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
        ) {
            if (!mine) {
                if (grouped) {
                    // Reserve the avatar slot so grouped bubbles stay aligned.
                    Spacer(Modifier.width(AVATAR_MSG_SIZE.dp).padding(end = AVATAR_GAP.dp))
                } else {
                    MessageAvatar(
                        viewModel = viewModel,
                        actorId = message.actorId,
                        modifier = Modifier.padding(end = AVATAR_GAP.dp)
                    )
                }
            }
            Box {
                Column(bubbleModifier) {
                    if (isImage && fileId != null) {
                        ImageAttachment(fileId, fileName, viewModel, onOpenImage)
                    } else {
                        Text(
                            if (fileName != null) "📎 $fileName" else message.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.text
                        )
                    }
                    if (message.timestamp > 0) {
                        Row(
                            Modifier.align(Alignment.End),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (read) {
                                val readLabel = stringResource(R.string.link_read)
                                Text(
                                    "✓✓",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.time,
                                    modifier = Modifier
                                        .padding(end = READ_TICK_GAP.dp)
                                        .semantics { contentDescription = readLabel }
                                )
                            }
                            Text(
                                formatTime(message.timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.time
                            )
                        }
                    }
                }
                DropdownMenu(expanded = contextMenuOpen, onDismissRequest = { contextMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Kopieren") },
                        onClick = {
                            contextMenuOpen = false
                            onCopy(message.message)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Zitieren") },
                        onClick = {
                            contextMenuOpen = false
                            onReply(message.actorDisplayName ?: message.actorId ?: "")
                        }
                    )
                }
            }
        }
    }
}

/** Avatar of a chat participant: real profile picture via `avatar/{actorId}`, initial as fallback. */
@Composable
private fun MessageAvatar(viewModel: LinkViewModel, actorId: String?, modifier: Modifier = Modifier) {
    val bytes by androidx.compose.runtime.produceState<ByteArray?>(null, actorId) {
        value = actorId?.let { viewModel.loadAvatar(it, AVATAR_MSG_SIZE) }
    }
    val bitmap = bytes?.toImageBitmap()
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = modifier.size(AVATAR_MSG_SIZE.dp).clip(CircleShape)
        )
    } else {
        val palette = AVATAR_PALETTE
        val color = palette[(actorId ?: "?").hashCode().mod(palette.size)]
        Box(
            modifier.size(AVATAR_MSG_SIZE.dp).clip(CircleShape).background(color),
            contentAlignment = Alignment.Center
        ) {
            Text(
                actorId?.trim()?.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White
            )
        }
    }
}

@Composable
private fun SwipeReplyHint(direction: SwipeToDismissBoxValue, colors: ChatColors) {
    val alpha by animateFloatAsState(
        if (direction == SwipeToDismissBoxValue.Settled) 0f else 1f,
        label = "replyHintAlpha"
    )
    Box(
        Modifier.fillMaxSize().alpha(alpha),
        contentAlignment = if (direction == SwipeToDismissBoxValue.EndToStart) {
            Alignment.CenterEnd
        } else {
            Alignment.CenterStart
        }
    ) {
        Surface(color = colors.mine, shape = CircleShape, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text(
                "↩",
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
                modifier = Modifier.padding(horizontal = SWIPE_HINT_PAD_H.dp, vertical = SWIPE_HINT_PAD_V.dp)
            )
        }
    }
}

@Composable
private fun FailedMessageBubble(text: String, colors: ChatColors, retrying: Boolean, onRetry: () -> Unit) {
    val shape = RoundedCornerShape(BUBBLE_CORNER.dp, TAIL_CORNER.dp, BUBBLE_CORNER.dp, BUBBLE_CORNER.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = BUBBLE_H.dp, vertical = BUBBLE_V.dp),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(color = colors.other.copy(alpha = 0.55f), shape = shape) {
            Column(Modifier.padding(horizontal = BUBBLE_PAD_H.dp, vertical = BUBBLE_PAD_V.dp)) {
                Text(text, style = MaterialTheme.typography.bodyLarge, color = colors.time)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.link_not_sent),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.time
                    )
                    IconButton(
                        onClick = onRetry,
                        enabled = !retrying,
                        modifier = Modifier.size(FAILED_RETRY_SIZE.dp)
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.link_retry),
                            tint = if (retrying) colors.time.copy(alpha = 0.4f) else colors.time
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageAttachment(
    fileId: String,
    fileName: String?,
    viewModel: LinkViewModel,
    onOpenImage: (androidx.compose.ui.graphics.ImageBitmap) -> Unit
) {
    val thumb by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, fileId) {
        value = viewModel.loadPreview(fileId, PREVIEW_SIZE)?.toImageBitmap()
    }
    val bitmap = thumb
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap,
            contentDescription = fileName,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.size(PREVIEW_DP.dp).clip(RoundedCornerShape(IMAGE_CORNER.dp))
                .clickable { onOpenImage(bitmap) }
        )
    } else {
        Text("📎 ${fileName ?: ""}", style = MaterialTheme.typography.bodyLarge)
    }
}

private fun ByteArray.toImageBitmap(): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    android.graphics.BitmapFactory.decodeByteArray(this, 0, size)?.asImageBitmap()
}.getOrNull()

@Composable
private fun InputBar(
    input: String,
    colors: ChatColors,
    onInput: (String) -> Unit,
    onEmoji: () -> Unit,
    onAttach: () -> Unit,
    onSend: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.fillMaxWidth().padding(horizontal = INPUT_H.dp, vertical = INPUT_V.dp)
    ) {
        Surface(
            color = colors.other,
            shape = RoundedCornerShape(PILL_CORNER.dp),
            modifier = Modifier.weight(1f)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onEmoji) {
                    Icon(
                        Icons.Outlined.Face,
                        contentDescription = stringResource(R.string.link_emoji),
                        tint = colors.time
                    )
                }
                TextField(
                    value = input,
                    onValueChange = onInput,
                    placeholder = { Text(stringResource(R.string.link_message_hint)) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    maxLines = INPUT_MAX_LINES,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onAttach) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(R.string.link_attach),
                        tint = colors.time
                    )
                }
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = CircleShape,
            modifier = Modifier.padding(start = SEND_GAP.dp).size(SEND_SIZE.dp)
        ) {
            IconButton(onClick = onSend) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.link_send),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

@Composable
private fun DateSeparator(label: String, colors: ChatColors) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(color = colors.time.copy(alpha = 0.15f), shape = RoundedCornerShape(12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = colors.time,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun ReplyQuoteBar(actorName: String, colors: ChatColors, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(colors.mine.copy(alpha = 0.3f)).padding(horizontal = INPUT_H.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "↩ $actorName",
            style = MaterialTheme.typography.bodySmall,
            color = colors.time,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onDismiss) {
            Text("✕", color = colors.time, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun EmojiPicker(onPick: (String) -> Unit) {
    AndroidView(
        factory = { ctx ->
            androidx.emoji2.emojipicker.EmojiPickerView(ctx).apply {
                setOnEmojiPickedListener { item -> onPick(item.emoji) }
            }
        },
        modifier = Modifier.fillMaxWidth().height(EMOJI_PANEL_HEIGHT.dp)
    )
}

private fun formatTime(unixSeconds: Long): String {
    val formatter = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return formatter.format(java.util.Date(unixSeconds * 1000))
}

private const val LIST_V = 8
private const val INPUT_H = 8
private const val INPUT_V = 6
private const val INPUT_MAX_LINES = 5
private const val PILL_CORNER = 24
private const val SEND_GAP = 6
private const val SEND_SIZE = 48
private const val BUBBLE_H = 8
private const val BUBBLE_V = 2
private const val BUBBLE_MAX = 300
private const val BUBBLE_CORNER = 16
private const val TAIL_CORNER = 4
private const val BUBBLE_PAD_H = 10
private const val BUBBLE_PAD_V = 6
private const val SWIPE_HINT_PAD_H = 12
private const val SWIPE_HINT_PAD_V = 8
private const val READ_TICK_GAP = 3
private const val EMOJI_PANEL_HEIGHT = 240
private const val STATUS_DOT = 8
private const val STATUS_DOT_GAP = 4
private const val FAILED_RETRY_SIZE = 48
private const val CALL_ROW_V = 14
private const val CALL_ROW_GAP = 20
private const val BANNER_PAD = 14
private const val CALL_POLL_MS = 5000L
private const val UPLOAD_SPINNER = 18
private const val UPLOAD_STROKE = 2
private const val PREVIEW_SIZE = 600
private const val PREVIEW_DP = 200
private const val IMAGE_CORNER = 10
private const val IMAGE_MODAL_PAD = 12
private const val AVATAR_MSG_SIZE = 32
private const val AVATAR_GAP = 8

private val AVATAR_PALETTE = listOf(
    Color(0xFF00897B),
    Color(0xFF3949AB),
    Color(0xFF8E24AA),
    Color(0xFFD81B60),
    Color(0xFFF4511E),
    Color(0xFF43A047),
    Color(0xFF1E88E5),
    Color(0xFF6D4C41)
)
