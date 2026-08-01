/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.ui

import android.accounts.Account
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.souvera.workspace.dav.DavAccount
import com.souvera.workspace.dav.SouveraSyncManager
import android.net.Uri
import com.souvera.workspace.link.net.LinkChatMessage
import com.souvera.workspace.link.net.LinkConversation
import com.souvera.workspace.link.net.LinkSuggestion
import com.souvera.workspace.link.net.OcsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the native "Link" (Nextcloud Talk) chat: loads the conversation list, opens a chat, and
 * keeps it live via the Talk chat long-poll (lookIntoFuture=1, 30s). Auth is the account
 * app-password resolved through [SouveraSyncManager] - the same credential the DAV features use.
 */
class LinkViewModel(application: Application) : AndroidViewModel(application) {

    private var dav: DavAccount? = null
    private var api: OcsApi? = null
    private var pollJob: Job? = null
    private var peerStatusJob: Job? = null
    private var lastMessageId = 0L
    private var conversationLoadSeq = 0L
    private var lastPeerStatusRefreshMs = 0L
    private val peerIdByToken = mutableMapOf<String, String?>()
    private var localIdCounter = 0L

    private fun nextLocalId(): Long = ++localIdCounter

    var currentUserId: String = ""
        private set

    private val _userResults = MutableStateFlow<List<LinkSuggestion>>(emptyList())
    val userResults: StateFlow<List<LinkSuggestion>> = _userResults.asStateFlow()

    private val _route = MutableStateFlow<LinkRoute>(LinkRoute.Home)
    val route: StateFlow<LinkRoute> = _route.asStateFlow()

    private val _conversations = MutableStateFlow<LinkUiState<List<LinkConversation>>>(LinkUiState.Loading)
    val conversations: StateFlow<LinkUiState<List<LinkConversation>>> = _conversations.asStateFlow()

    private val _messages = MutableStateFlow<LinkUiState<List<LinkChatMessage>>>(LinkUiState.Loading)
    val messages: StateFlow<LinkUiState<List<LinkChatMessage>>> = _messages.asStateFlow()

    /** Highest message id the chat peer has read (read receipt), null while unknown. */
    private val _readUpTo = MutableStateFlow<Long?>(null)
    val readUpTo: StateFlow<Long?> = _readUpTo.asStateFlow()

    /** Presence of the one-to-one chat peer, null while unknown / in group rooms. */
    private val _peerStatus = MutableStateFlow<com.souvera.workspace.status.PeerStatus?>(null)
    val peerStatus: StateFlow<com.souvera.workspace.status.PeerStatus?> = _peerStatus.asStateFlow()

    private val _chatPeerId = MutableStateFlow<String?>(null)
    val chatPeerId: StateFlow<String?> = _chatPeerId.asStateFlow()

    /** Resolved peer user IDs for 1:1 conversation tokens (cached, lazy). */
    private val peerIdCache = mutableMapOf<String, String?>()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    val baseUrl: String get() = dav?.baseUrl ?: ""

    /** Locally tracked messages whose send failed; kept until a retry succeeds. */
    private val _failedMessages = MutableStateFlow<List<FailedChatMessage>>(emptyList())
    val failedMessages: StateFlow<List<FailedChatMessage>> = _failedMessages.asStateFlow()

    /** Local ids currently being re-sent (retry button disabled while in flight). */
    private val _retryInFlight = MutableStateFlow<Set<Long>>(emptySet())
    val retryInFlight: StateFlow<Set<Long>> = _retryInFlight.asStateFlow()

    private val _uploading = MutableStateFlow(false)
    val uploading: StateFlow<Boolean> = _uploading.asStateFlow()

    fun start(account: Account) {
        if (api != null) return
        val resolved = SouveraSyncManager(getApplication()).resolve(account)
        if (resolved == null) {
            _conversations.value = LinkUiState.Error("No account")
            return
        }
        dav = resolved
        currentUserId = resolved.username
        api = OcsApi(resolved)
        loadConversations()
    }

    fun searchUsers(query: String) {
        val client = api ?: return
        if (query.isBlank()) {
            _userResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            _userResults.value = runCatching { withContext(Dispatchers.IO) { client.searchUsers(query.trim()) } }
                .getOrDefault(emptyList())
        }
    }

    fun startConversation(id: String, source: String, title: String) {
        val client = api ?: return
        val roomType = if (source == "groups") ROOM_TYPE_GROUP else ROOM_TYPE_ONE_TO_ONE
        viewModelScope.launch {
            val token = withContext(Dispatchers.IO) { client.createConversation(id, roomType) }
            _userResults.value = emptyList()
            if (token != null) {
                loadConversations()
                openConversation(token, title)
            }
        }
    }

    suspend fun loadPreview(fileId: String, size: Int): ByteArray? =
        withContext(Dispatchers.IO) { runCatching { api?.previewBytes(fileId, size) }.getOrNull() }

    private val avatarCache = mutableMapOf<String, ByteArray>()

    /** Downloads a user avatar by NC user id, or null on failure. Cached per actor+size. */
    suspend fun loadAvatar(actorId: String, size: Int): ByteArray? {
        val key = "$actorId@$size"
        avatarCache[key]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { api?.avatarBytes(actorId, size) }.getOrNull()
        }?.also { avatarCache[key] = it }
    }

    /** Downloads a shared file to the cache and opens it with the system viewer (not the browser). */
    fun openSharedFile(message: LinkChatMessage) {
        val client = api ?: return
        val path = message.filePath() ?: return
        val name = message.fileName() ?: "attachment"
        val mime = message.fileMimeType() ?: "application/octet-stream"
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { client.downloadFile(path) }.getOrNull() }
            if (bytes == null) return@launch
            withContext(Dispatchers.IO) {
                runCatching {
                    val app = getApplication<Application>()
                    val dir = java.io.File(app.cacheDir, "link-files").apply { mkdirs() }
                    val file = java.io.File(dir, name)
                    file.writeBytes(bytes)
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        app,
                        app.getString(com.owncloud.android.R.string.file_provider_authority),
                        file
                    )
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                        .setDataAndType(uri, mime)
                        .addFlags(
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    app.startActivity(
                        android.content.Intent.createChooser(
                            intent,
                            name
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
    }

    fun sendAttachment(uri: Uri) {
        val client = api ?: return
        val token = (_route.value as? LinkRoute.Chat)?.token ?: return
        _uploading.value = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching
                    val name = queryDisplayName(uri)
                    val mime = resolver.getType(uri) ?: "application/octet-stream"
                    client.shareFile(token, name, mime, bytes)
                }
            }
            _uploading.value = false
            runCatching { pollNewMessages(token) }
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                cursor.getString(index)?.let { return it }
            }
        }
        return "attachment_${System.currentTimeMillis()}"
    }

    fun loadConversations() {
        val client = api ?: return
        val seq = ++conversationLoadSeq
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                val result = runCatching { withContext(Dispatchers.IO) { client.listConversations() } }
                    .fold(
                        { list -> LinkUiState.Success(list.sortedByDescending { it.lastActivity }) },
                        { LinkUiState.Error(it.message ?: "Error") }
                    )
                if (seq != conversationLoadSeq) return@launch
                _conversations.value = result
                val chat = _route.value as? LinkRoute.Chat
                val conversations = (result as? LinkUiState.Success)?.data.orEmpty()
                _readUpTo.value = conversations.firstOrNull { it.token == chat?.token }?.lastCommonReadMessage
                // Pre-resolve peer IDs for 1:1 chats (avatar in conversation list).
                resolvePeerIdsForAvatars(conversations)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /** Fills [peerIdCache] for 1:1 conversations (lazy background resolution). */
    private fun resolvePeerIdsForAvatars(conversations: List<LinkConversation>) {
        val client = api ?: return
        val me = currentUserId ?: return
        viewModelScope.launch {
            conversations.filter { it.type == ROOM_TYPE_ONE_TO_ONE && it.token !in peerIdCache }.forEach { c ->
                runCatching {
                    withContext(Dispatchers.IO) { client.getPeerUserId(c.token, me) }
                }.getOrNull()?.let { peerIdCache[c.token] = it }
            }
        }
    }

    /** Returns the resolved peer user ID for a conversation, or null. */
    fun peerIdFor(token: String): String? = peerIdCache[token]

    fun openConversation(token: String, title: String) {
        _route.value = LinkRoute.Chat(token, title)
        _messages.value = LinkUiState.Loading
        _readUpTo.value = null
        _peerStatus.value = null
        lastMessageId = 0L
        val client = api ?: return
        pollJob?.cancel()
        peerStatusJob?.cancel()
        pollJob = viewModelScope.launch {
            // Load the newest messages: lookIntoFuture=0 pages backwards from a high anchor id, so
            // it returns the most recent page (there is no "give me the latest" without an anchor).
            val history = runCatching {
                withContext(Dispatchers.IO) {
                    client.getMessages(token, HISTORY_ANCHOR, future = false, timeoutSeconds = 0)
                }
            }.getOrElse {
                _messages.value = LinkUiState.Error(it.message ?: "Error")
                return@launch
            }
            val ordered = history.sortedBy { it.id }
            lastMessageId = ordered.lastOrNull()?.id ?: 0L
            _messages.value = LinkUiState.Success(ordered)
            dropFailedMatchedByReferenceId(ordered)
            loadConversations()
            loadPeerStatus(token)
            pollNewMessages(token)
        }
    }

    /** Refreshes the one-to-one peer presence; null in group rooms / on errors. Single-flight. */
    fun loadPeerStatus(token: String) {
        val client = api ?: return
        val dav = dav ?: return
        peerStatusJob?.cancel()
        peerStatusJob = viewModelScope.launch {
            val conversation = (_conversations.value as? LinkUiState.Success)?.data
                ?.firstOrNull { it.token == token }
            if (conversation != null && conversation.type != ROOM_TYPE_ONE_TO_ONE) {
                if ((_route.value as? LinkRoute.Chat)?.token == token) {
                    _peerStatus.value = null
                    _chatPeerId.value = null
                }
                return@launch
            }
            val peer = peerIdByToken[token] ?: run {
                val found = try {
                    withContext(Dispatchers.IO) { client.getPeerUserId(token, currentUserId) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (found != null) peerIdByToken[token] = found
                found
            }
            if ((_route.value as? LinkRoute.Chat)?.token != token) return@launch
            if (peer == null) {
                _peerStatus.value = null
                _chatPeerId.value = null
                return@launch
            }
            _chatPeerId.value = peer
            val parsed = try {
                withContext(Dispatchers.IO) { com.souvera.workspace.status.StatusApi(dav).peerStatus(peer) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if ((_route.value as? LinkRoute.Chat)?.token != token) return@launch
            val conversationLastActivity = (_conversations.value as? LinkUiState.Success)?.data
                ?.firstOrNull { it.token == token }?.lastActivity ?: 0L
            _peerStatus.value = parsed?.let {
                it.copy(lastActivity = if (conversationLastActivity > 0) conversationLastActivity else it.lastActivity)
            }
        }
    }

    private suspend fun pollNewMessages(token: String) {
        val client = api ?: return
        while (viewModelScope.isActive && (_route.value as? LinkRoute.Chat)?.token == token) {
            val fresh = try {
                withContext(Dispatchers.IO) {
                    client.getMessages(token, lastMessageId, future = true, timeoutSeconds = POLL_TIMEOUT)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (fresh.isNotEmpty()) {
                lastMessageId = fresh.maxOf { it.id }
                val current = (_messages.value as? LinkUiState.Success)?.data.orEmpty()
                _messages.value = LinkUiState.Success((current + fresh).distinctBy { it.id }.sortedBy { it.id })
                dropFailedMatchedByReferenceId(fresh)
            }
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastPeerStatusRefreshMs > PEER_STATUS_REFRESH_MS) {
                loadPeerStatus(token)
                lastPeerStatusRefreshMs = now
            }
        }
    }

    fun send(text: String) {
        val client = api ?: return
        val token = (_route.value as? LinkRoute.Chat)?.token ?: return
        if (text.isBlank()) return
        val trimmed = text.trim()
        val localId = nextLocalId()
        val referenceId = referenceIdFor(localId)
        val timestamp = System.currentTimeMillis() / 1000
        viewModelScope.launch {
            val ok = try {
                withContext(Dispatchers.IO) { client.sendMessage(token, trimmed, referenceId) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (ok) return@launch
            _failedMessages.value = _failedMessages.value + FailedChatMessage(
                localId = localId,
                token = token,
                text = trimmed,
                timestamp = timestamp
            )
        }
    }

    /** Re-sends a failed message; removes it from the list once the server accepted it. */
    fun retryMessage(localId: Long) {
        val client = api ?: return
        val failed = _failedMessages.value.firstOrNull { it.localId == localId } ?: return
        if (localId in _retryInFlight.value) return
        _retryInFlight.value = _retryInFlight.value + localId
        viewModelScope.launch {
            try {
                val ok = try {
                    withContext(Dispatchers.IO) {
                        client.sendMessage(failed.token, failed.text, referenceIdFor(localId))
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                if (ok) {
                    _failedMessages.value = _failedMessages.value.filterNot { it.localId == localId }
                }
            } finally {
                _retryInFlight.value = _retryInFlight.value - localId
            }
        }
    }

    private fun referenceIdFor(localId: Long): String = "souv-$localId"

    /**
     * Drops failed entries whose message was already accepted server-side (response lost) — the
     * message surfaces in history/poll with its referenceId; re-sending would create a duplicate.
     */
    private fun dropFailedMatchedByReferenceId(messages: List<LinkChatMessage>) {
        val matchedLocalIds = messages.mapNotNull { it.referenceId }.mapNotNull { ref ->
            if (ref.startsWith(REFERENCE_PREFIX)) ref.removePrefix(REFERENCE_PREFIX).toLongOrNull() else null
        }
        if (matchedLocalIds.isNotEmpty()) {
            _failedMessages.value = _failedMessages.value.filterNot { it.localId in matchedLocalIds }
        }
    }

    fun back(): Boolean {
        if (_route.value is LinkRoute.Chat) {
            pollJob?.cancel()
            _route.value = LinkRoute.Home
            loadConversations()
            return true
        }
        return false
    }

    companion object {
        private const val POLL_TIMEOUT = 30
        private const val ROOM_TYPE_ONE_TO_ONE = 1
        private const val ROOM_TYPE_GROUP = 2
        private const val HISTORY_ANCHOR = 2_000_000_000L
        private const val PEER_STATUS_REFRESH_MS = 30_000L
        private const val REFERENCE_PREFIX = "souv-"
    }
}
