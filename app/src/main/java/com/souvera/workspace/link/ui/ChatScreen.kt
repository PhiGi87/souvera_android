/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
    val state by viewModel.messages.collectAsState()
    var input by remember { mutableStateOf("") }
    var emojiOpen by remember { mutableStateOf(false) }
    var callDialogOpen by remember { mutableStateOf(false) }
    var modalImage by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    val listState = rememberLazyListState()
    val messages = (state as? LinkUiState.Success)?.data.orEmpty().filter { it.systemMessage.isEmpty() }
    val me = viewModel.currentUserId
    val colors = ChatColors(isSystemInDarkTheme())
    val context = LocalContext.current

    val conversations by viewModel.conversations.collectAsState()
    val uploading by viewModel.uploading.collectAsState()
    val hasActiveCall = (conversations as? LinkUiState.Success)?.data
        ?.firstOrNull { it.token == route.token }?.hasCall == true

    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.sendAttachment(it) }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
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
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(route.title) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { callDialogOpen = true }) {
                        Icon(Icons.Filled.Phone, contentDescription = stringResource(R.string.link_call))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(colors.background)) {
            if (hasActiveCall) {
                IncomingCallBanner(onJoin = { startCall(context, route, withVideo = true) })
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = LIST_V.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message, message.actorId == me, colors, viewModel) { img -> modalImage = img }
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
            if (emojiOpen) EmojiPicker(colors) { input += it }
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
        androidx.compose.ui.window.Dialog(onDismissRequest = { modalImage = null }) {
            Box(
                Modifier.fillMaxSize().background(Color(0xE6000000)).clickable { modalImage = null },
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().padding(IMAGE_MODAL_PAD.dp)
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

@Composable
private fun MessageBubble(
    message: LinkChatMessage,
    mine: Boolean,
    colors: ChatColors,
    viewModel: LinkViewModel,
    onOpenImage: (androidx.compose.ui.graphics.ImageBitmap) -> Unit
) {
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
    if (onClick != null) bubbleModifier = bubbleModifier.clickable(onClick = onClick)
    bubbleModifier = bubbleModifier.padding(horizontal = BUBBLE_PAD_H.dp, vertical = BUBBLE_PAD_V.dp)

    Row(
        Modifier.fillMaxWidth().padding(horizontal = BUBBLE_H.dp, vertical = BUBBLE_V.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
    ) {
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
                Text(
                    formatTime(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.time,
                    modifier = Modifier.align(Alignment.End)
                )
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
private fun EmojiPicker(colors: ChatColors, onPick: (String) -> Unit) {
    Surface(color = colors.other, tonalElevation = EMOJI_ELEVATION.dp) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(EMOJI_CELL.dp),
            modifier = Modifier.fillMaxWidth().height(EMOJI_PANEL_HEIGHT.dp),
            contentPadding = PaddingValues(EMOJI_PANEL_PAD.dp)
        ) {
            items(EMOJIS) { emoji ->
                Box(
                    Modifier.size(EMOJI_CELL.dp).clip(CircleShape).clickable { onPick(emoji) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(emoji, style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
    }
}

private fun formatTime(unixSeconds: Long): String {
    val formatter = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return formatter.format(java.util.Date(unixSeconds * 1000))
}

private val EMOJIS = listOf(
    "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇",
    "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘", "😗", "😙", "😚",
    "😋", "😛", "😝", "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🥳",
    "🤩", "😏", "😒", "😞", "😔", "😟", "😕", "🙁", "☹️", "😣",
    "😖", "😫", "😩", "🥺", "😢", "😭", "😤", "😠", "😡", "🤬",
    "🤯", "😳", "🥵", "🥶", "😱", "😨", "😰", "😥", "🤗", "🤔",
    "🤭", "🤫", "😶", "😐", "😑", "😬", "🙄", "😯", "😴", "🤤",
    "👍", "👎", "👌", "✌️", "🤞", "🙏", "👏", "🙌", "💪", "🔥",
    "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "💯", "✅", "❌",
    "🎉", "🎊", "👀", "⭐", "🌟", "💥", "☀️", "🌈", "☕", "🍕"
)

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
private const val EMOJI_CELL = 44
private const val EMOJI_PANEL_HEIGHT = 240
private const val EMOJI_PANEL_PAD = 8
private const val EMOJI_ELEVATION = 3
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
