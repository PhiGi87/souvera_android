/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.ui

import android.accounts.Account
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nextcloud.common.NextcloudClient
import com.owncloud.android.R
import com.owncloud.android.lib.resources.users.GetUserInfoRemoteOperation
import com.souvera.workspace.dav.DavAccount
import com.souvera.workspace.mail.MailSettings
import com.souvera.workspace.mail.SouveraMailCredentialManager
import com.souvera.workspace.mail.db.entity.MailboxEntity
import com.souvera.workspace.mail.db.entity.MailboxKind
import com.souvera.workspace.mail.db.entity.NamespaceType
import com.souvera.workspace.mail.db.entity.MessageEntity
import com.souvera.workspace.mail.model.MessageBody
import com.souvera.workspace.mail.model.OutgoingMessage
import com.souvera.workspace.mail.net.mailboxPath
import com.souvera.workspace.mail.repository.MailResult
import com.souvera.workspace.mail.repository.MailboxRepository
import com.souvera.workspace.mail.repository.MessageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials

@Suppress("TooManyFunctions")
class MailViewModel(application: Application) : AndroidViewModel(application) {

    private val mailboxRepository = MailboxRepository(application)
    private val messageRepository = MessageRepository(application)
    private val credentialManager = SouveraMailCredentialManager(application)
    private val settings = MailSettings(application)

    private lateinit var account: Account
    private var dav: DavAccount? = null

    private val _route = MutableStateFlow<MailRoute>(MailRoute.Home)
    val route: StateFlow<MailRoute> = _route.asStateFlow()

    private val _mailboxes = MutableStateFlow<MailUiState<List<MailboxEntity>>>(MailUiState.Loading)
    val mailboxes: StateFlow<MailUiState<List<MailboxEntity>>> = _mailboxes.asStateFlow()

    private val _currentMailbox = MutableStateFlow<MailboxEntity?>(null)
    val currentMailbox: StateFlow<MailboxEntity?> = _currentMailbox.asStateFlow()

    private val _messages = MutableStateFlow<MailUiState<List<MessageEntity>>>(MailUiState.Loading)
    val messages: StateFlow<MailUiState<List<MessageEntity>>> = _messages.asStateFlow()

    private val _body = MutableStateFlow<MailUiState<MessageBody>>(MailUiState.Loading)
    val body: StateFlow<MailUiState<MessageBody>> = _body.asStateFlow()

    private val _sendState = MutableStateFlow<SendState>(SendState.Idle)
    val sendState: StateFlow<SendState> = _sendState.asStateFlow()

    private val _fromAddress = MutableStateFlow("")
    val fromAddress: StateFlow<String> = _fromAddress.asStateFlow()

    private val _fromIdentities = MutableStateFlow(listOf<String>())
    val fromIdentities: StateFlow<List<String>> = _fromIdentities.asStateFlow()

    private val _selectedFrom = MutableStateFlow("")
    val selectedFrom: StateFlow<String> = _selectedFrom.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _credentialFailed = MutableStateFlow(false)
    val credentialFailed: StateFlow<Boolean> = _credentialFailed.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val searchResults: StateFlow<List<MessageEntity>> = _searchQuery
        .flatMapLatest { query ->
            val currentDav = dav
            if (this::account.isInitialized && query.isNotBlank() && currentDav != null) {
                flow {
                    emit(messageRepository.searchMessages(account.name, query.trim(), currentDav))
                }
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SEARCH_STOP_TIMEOUT_MS), emptyList())

    private val backStack = ArrayDeque<MailRoute>()
    private var messagesJob: Job? = null
    private var syncJob: Job? = null
    private var credentialJob: Job? = null
    private var credentialResolved = false
    private var appliedMessageLimit = 0
    private var pendingDeepLink: Pair<String, String>? = null

    fun start(account: Account) {
        if (credentialResolved) return
        this.account = account
        resolveCombinedCredential()
    }

    /** Retry credential resolution after a login failure. */
    fun retryLogin() {
        if (!this::account.isInitialized) return
        _credentialFailed.value = false
        _mailboxes.value = MailUiState.Loading
        _messages.value = MailUiState.Loading
        resolveCombinedCredential()
    }

    private fun resolveCombinedCredential() {
        credentialJob?.cancel()
        credentialJob = viewModelScope.launch {
            @Suppress("TooGenericExceptionCaught")
            val resolved = try {
                credentialManager.ensureCombinedCredential(account)
            } catch (e: Exception) {
                val message = getApplication<Application>()
                    .getString(R.string.mail_credential_failed, e.message ?: e.javaClass.simpleName)
                _mailboxes.value = MailUiState.Error(message)
                _messages.value = MailUiState.Error(message)
                _credentialFailed.value = true
                return@launch
            }
            if (resolved == null) {
                _mailboxes.value =
                    MailUiState.Error(getApplication<Application>().getString(R.string.souvera_no_account))
                _credentialFailed.value = true
                return@launch
            }
            credentialResolved = true
            dav = resolved
            // A push deep link requested before credential init completed is
            // replayed here (dav was still null when openMessageByEmailId ran).
            pendingDeepLink?.let { (path, id) ->
                pendingDeepLink = null
                openMessageByEmailId(path, id)
            }
            launch { _fromAddress.value = resolveFromAddress(resolved) }
            launch {
                mailboxRepository.observeMailboxes(account.name).collect { list ->
                    if (list.isNotEmpty()) {
                        showMailboxes(list)
                        val owners = list.mapNotNull { it.ownerIdentity }.distinct()
                        val userIdentity = _fromAddress.value.ifBlank { resolved.username }
                        val all = (listOf(userIdentity) + owners.filter { it != userIdentity }).distinct()
                        _fromIdentities.value = all
                        if (_selectedFrom.value.isBlank()) _selectedFrom.value = userIdentity
                    }
                }
            }
            when (val result = mailboxRepository.syncMailboxes(account.name, resolved)) {
                is MailResult.Failure ->
                    if (_mailboxes.value !is MailUiState.Success) {
                        _mailboxes.value = MailUiState.Error(result.message)
                        if (_currentMailbox.value == null) _messages.value = MailUiState.Error(result.message)
                    }

                is MailResult.Success -> {
                    if (_mailboxes.value !is MailUiState.Success) showMailboxes(result.value)
                    launch(Dispatchers.IO) { mailboxRepository.refreshMailboxCounts(account.name, resolved) }
                }
            }
        }
    }

    private fun showMailboxes(list: List<MailboxEntity>) {
        _mailboxes.value = MailUiState.Success(list)
        if (_currentMailbox.value == null) {
            val personalInbox = list.firstOrNull {
                it.kind == MailboxKind.INBOX && it.namespaceType == NamespaceType.PERSONAL
            }
            val inbox = personalInbox ?: list.firstOrNull { it.kind == MailboxKind.INBOX } ?: list.firstOrNull()
            if (inbox != null) selectMailbox(inbox) else _messages.value = MailUiState.Success(emptyList())
        }
    }

    fun back(): Boolean {
        val previous = backStack.removeLastOrNull() ?: return false
        _route.value = previous
        return true
    }

    fun navigate(route: MailRoute) {
        if (route is MailRoute.Search) _searchQuery.value = ""
        backStack.addLast(_route.value)
        _route.value = route
    }

    fun selectMailbox(mailbox: MailboxEntity) {
        _currentMailbox.value = mailbox
        _messages.value = MailUiState.Loading
        appliedMessageLimit = settings.messageLimit
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            launch {
                messageRepository.observeMessages(account.name, mailbox.path).collect { list ->
                    if (list.isNotEmpty()) _messages.value = MailUiState.Success(list)
                }
            }
            syncMailbox(mailbox.path)
        }
    }

    fun refresh() {
        val mailbox = _currentMailbox.value ?: return
        if (settings.messageLimit != appliedMessageLimit) {
            selectMailbox(mailbox)
            return
        }
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            try {
                syncMailbox(mailbox.path)
            } finally {
                syncJob = null
            }
        }
    }

    /** Quietly re-syncs the current mailbox (no refresh indicator) for periodic/push-driven updates.
     *  Skips if a sync is already running (dedup) to avoid stacking parallel IMAP connections. */
    fun autoRefresh() {
        val mailbox = _currentMailbox.value ?: return
        if (settings.messageLimit != appliedMessageLimit) return
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            try {
                syncMailbox(mailbox.path, showIndicator = false)
            } finally {
                syncJob = null
            }
        }
    }

    fun openMessage(message: MessageEntity) {
        val current = dav ?: return
        navigate(MailRoute.Detail(message))
        _body.value = MailUiState.Loading
        val path = message.mailboxPath()
        viewModelScope.launch {
            _body.value = when (val result = messageRepository.fetchMessageBody(path, message.emailId, current)) {
                is MailResult.Success -> MailUiState.Success(result.value)
                is MailResult.Failure -> MailUiState.Error(result.message)
            }
            if (!message.isRead) {
                messageRepository.setRead(account.name, path, message.emailId, true, current)
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun send(outgoing: OutgoingMessage) {
        val current = dav ?: return
        _sendState.value = SendState.Sending
        viewModelScope.launch {
            val from = _selectedFrom.value.ifBlank { _fromAddress.value.ifBlank { current.username } }
            _sendState.value =
                when (val result = messageRepository.sendMessage(account.name, current, from, outgoing)) {
                    is MailResult.Success -> SendState.Sent
                    is MailResult.Failure -> SendState.Error(result.message)
                }
        }
    }

    fun consumeSendState() {
        _sendState.value = SendState.Idle
    }

    /**
     * "Spam"-Aktion: Mail in den Junk-Ordner verschieben und den Absender
     * über souvera_shield auf die PMG-Blacklist setzen.
     */
    fun spamMessage(message: MessageEntity) {
        val current = dav ?: return
        if (_isDeleting.value) return
        viewModelScope.launch {
            _isDeleting.value = true
            try {
                val moveResult = messageRepository.spam(account.name, message.mailboxPath(), message.emailId, current)
                val sender = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+")
                    .find(message.fromAddress)?.value
                var blocked = true
                if (sender != null) {
                    blocked = withContext(Dispatchers.IO) {
                        runCatching { com.souvera.workspace.shield.ShieldApi(current).blacklist(sender) }.getOrDefault(false)
                    }
                }
                if (moveResult is MailResult.Success) {
                    if (blocked) {
                        Toast.makeText(
                            getApplication<Application>(),
                            getApplication<Application>().getString(R.string.mail_spam_done),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    back()
                } else {
                    val app = getApplication<Application>()
                    Toast.makeText(
                        app,
                        (moveResult as? MailResult.Failure)?.message ?: app.getString(R.string.mail_spam_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                _isDeleting.value = false
            }
        }
    }

    fun selectFromIdentity(identity: String) {
        _selectedFrom.value = identity
    }

    fun deleteMessage(message: MessageEntity) {
        val current = dav ?: return
        if (_isDeleting.value) return
        viewModelScope.launch {
            _isDeleting.value = true
            try {
                val result = messageRepository.delete(account.name, message.mailboxPath(), message.emailId, current)
                if (result is MailResult.Success) {
                    back()
                } else {
                    val app = getApplication<Application>()
                    Toast.makeText(
                        app,
                        (result as? MailResult.Failure)?.message ?: app.getString(R.string.mail_delete_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                _isDeleting.value = false
            }
        }
    }

    /**
     * Opens the message referenced by a push notification ("open exactly this
     * mail" deep link). Resolves the cached entity by mailbox+uid and shows
     * the detail screen; falls back to the home screen when the message is
     * not (yet) in the local cache.
     */
    fun openMessageByEmailId(mailboxPath: String, emailId: String) {
        if (dav == null) {
            pendingDeepLink = mailboxPath to emailId
            return
        }
        val current = dav ?: return
        viewModelScope.launch {
            // "INBOX" aus dem Push-Payload auf den lokalen Posteingangs-Pfad
            // normalisieren (lokale IDs sind "$account:$name", case-sensitive).
            var path = mailboxPath
            if (path.isBlank() || path.equals("INBOX", ignoreCase = true)) {
                withContext(Dispatchers.IO) {
                    mailboxRepository.resolveInboxPath(account.name)
                }?.let { path = it }
            }
            var entity = withContext(Dispatchers.IO) {
                messageRepository.messageById(account.name, path, emailId)
            }
            if (entity == null) {
                messageRepository.syncMessages(account.name, path, current)
                entity = withContext(Dispatchers.IO) {
                    messageRepository.messageById(account.name, path, emailId)
                }
            }
            if (entity == null) {
                // Fallback: Mail wurde evtl. per Sieve in einen anderen Ordner
                // sortiert — accountweit suchen.
                entity = withContext(Dispatchers.IO) {
                    messageRepository.messageByEmailIdAnywhere(account.name, emailId)
                }
            }
            if (entity != null) {
                openMessage(entity)
            } else {
                _route.value = MailRoute.Home
            }
        }
    }

    fun toggleFlagged(message: MessageEntity, flagged: Boolean) {
        val current = dav ?: return
        viewModelScope.launch {
            messageRepository.setFlagged(account.name, message.mailboxPath(), message.emailId, flagged, current)
        }
    }

    fun openAttachment(message: MessageEntity, index: Int) {
        val current = dav ?: return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val result =
                messageRepository.fetchAttachment(message.mailboxPath(), message.emailId, index, current)
            when (result) {
                is MailResult.Success -> {
                    val authority = app.getString(R.string.file_provider_authority)
                    val file = result.value.file
                    val uri = FileProvider.getUriForFile(app, authority, file)
                    // MIME type straight from the IMAP part; fall back to the
                    // resolver and finally octet-stream so ACTION_VIEW always
                    // has a concrete type to match apps against.
                    val mime = result.value.mimeType
                        .takeIf { it.isNotBlank() }
                        ?: app.contentResolver.getType(uri)
                        ?: "application/octet-stream"
                    val intent = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        app.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(app, R.string.mail_attachment_no_app, Toast.LENGTH_LONG).show()
                    }
                }

                is MailResult.Failure -> Toast.makeText(app, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun syncMailbox(path: String, showIndicator: Boolean = true) {
        val current = dav ?: return
        if (showIndicator) _isRefreshing.value = true
        val result = messageRepository.syncMessages(account.name, path, current)
        if (showIndicator) _isRefreshing.value = false
        when {
            result is MailResult.Failure && _messages.value !is MailUiState.Success ->
                _messages.value = MailUiState.Error(result.message)

            result is MailResult.Success && result.value.isEmpty() ->
                _messages.value = MailUiState.Success(emptyList())
        }
        // Refresh unread/total counts so the folder dropdown badges update.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            mailboxRepository.refreshMailboxCounts(account.name, current)
        }
    }

    private suspend fun resolveFromAddress(dav: DavAccount): String = withContext(Dispatchers.IO) {
        try {
            val credentials = Credentials.basic(dav.username, dav.password)
            val client = NextcloudClient(Uri.parse(dav.baseUrl), dav.username, credentials, getApplication())
            val result = GetUserInfoRemoteOperation().execute(client)
            result.resultData?.email?.takeIf { it.isNotBlank() } ?: dav.username
        } catch (_: Exception) {
            dav.username
        }
    }

    companion object {
        private const val SEARCH_STOP_TIMEOUT_MS = 5_000L
    }
}
