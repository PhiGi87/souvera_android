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
    private var lastMessageId = 0L

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
        viewModelScope.launch {
            _conversations.value = runCatching { withContext(Dispatchers.IO) { client.listConversations() } }
                .fold({ LinkUiState.Success(it) }, { LinkUiState.Error(it.message ?: "Error") })
        }
    }

    fun openConversation(token: String, title: String) {
        _route.value = LinkRoute.Chat(token, title)
        _messages.value = LinkUiState.Loading
        lastMessageId = 0L
        val client = api ?: return
        pollJob?.cancel()
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
            pollNewMessages(token)
        }
    }

    private suspend fun pollNewMessages(token: String) {
        val client = api ?: return
        while (viewModelScope.isActive && (_route.value as? LinkRoute.Chat)?.token == token) {
            val fresh = runCatching {
                withContext(Dispatchers.IO) {
                    client.getMessages(token, lastMessageId, future = true, timeoutSeconds = POLL_TIMEOUT)
                }
            }.getOrDefault(emptyList())
            if (fresh.isNotEmpty()) {
                lastMessageId = fresh.maxOf { it.id }
                val current = (_messages.value as? LinkUiState.Success)?.data.orEmpty()
                _messages.value = LinkUiState.Success((current + fresh).distinctBy { it.id }.sortedBy { it.id })
            }
        }
    }

    fun send(text: String) {
        val client = api ?: return
        val token = (_route.value as? LinkRoute.Chat)?.token ?: return
        if (text.isBlank()) return
        viewModelScope.launch { withContext(Dispatchers.IO) { client.sendMessage(token, text.trim()) } }
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
    }
}
