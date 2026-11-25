package com.example.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.crypto.CipherMode
import com.example.crypto.KeystoreCryptoEngine
import com.example.data.local.KeyboardPreferences
import com.example.data.local.PreferencesRepository
import com.example.data.model.DecryptedVaultKey
import com.example.data.model.TranslationDirection
import com.example.data.repository.SyncState
import com.example.data.repository.VaultRepository
import com.example.features.TranslationEngine
import com.example.features.UpdateManager
import com.example.ui.keyboard.KeyboardStatusBanner
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

data class SandboxMessage(
    val id: String,
    val content: String,
    val isEncrypted: Boolean,
    val cipherMode: CipherMode? = null,
    val timestampLabel: String
)

class CipherVaultViewModel(
    private val currentUserId: String,
    private val vaultRepository: VaultRepository,
    private val prefsRepo: PreferencesRepository,
    private val translationEngine: TranslationEngine,
    private val updateManager: UpdateManager,
    private val cryptoEngine: KeystoreCryptoEngine
) : ViewModel() {

    val vaultKeysState: StateFlow<UiState<List<DecryptedVaultKey>>> =
        vaultRepository.observeLocalVaultKeys(currentUserId)
            .map<List<DecryptedVaultKey>, UiState<List<DecryptedVaultKey>>> { UiState.Success(it) }
            .catch { error ->
                if (error is CancellationException) throw error
                Log.w(TAG, "Error observing local vault keys", error)
                emit(UiState.Error(error.localizedMessage ?: "Failed to load local encrypted vault"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = UiState.Loading
            )

    val preferences: StateFlow<KeyboardPreferences> = prefsRepo.preferences
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = KeyboardPreferences()
        )

    val syncState: StateFlow<SyncState> = vaultRepository.syncState
    val isOnline: StateFlow<Boolean> = vaultRepository.isOnline

    private val _cloudError = MutableStateFlow<String?>(null)
    val cloudError: StateFlow<String?> = _cloudError.asStateFlow()

    private val _sandboxText = MutableStateFlow("")
    val sandboxText: StateFlow<String> = _sandboxText.asStateFlow()

    private val _sandboxMessages = MutableStateFlow<List<SandboxMessage>>(emptyList())
    val sandboxMessages: StateFlow<List<SandboxMessage>> = _sandboxMessages.asStateFlow()

    private val _keyboardBanner = MutableStateFlow<KeyboardStatusBanner?>(null)
    val keyboardBanner: StateFlow<KeyboardStatusBanner?> = _keyboardBanner.asStateFlow()

    private val _sessionCipherMode = MutableStateFlow<CipherMode?>(null)
    val sessionCipherMode: StateFlow<CipherMode?> = _sessionCipherMode.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _updateStatusMessage = MutableStateFlow<String?>(null)
    val updateStatusMessage: StateFlow<String?> = _updateStatusMessage.asStateFlow()

    private val _offlinePackMessage = MutableStateFlow<String?>(null)
    val offlinePackMessage: StateFlow<String?> = _offlinePackMessage.asStateFlow()

    init {
        viewModelScope.launch {
            vaultRepository.ensureOwnIdentity(currentUserId)
        }
        viewModelScope.launch {
            vaultRepository.observeCloudVaultStream(currentUserId)
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val rootError = firestoreError ?: error
                    Log.w(TAG, "Cloud sync stream interrupted", rootError)
                    _cloudError.value = rootError.localizedMessage
                }
                .collectLatest { remoteRecords ->
                    _cloudError.value = null
                    vaultRepository.reconcileRemoteRecords(currentUserId, remoteRecords)
                }
        }
        viewModelScope.launch {
            prefsRepo.preferences.collectLatest { prefs ->
                updateManager.scheduleBackgroundChecks(prefs.autoCheckUpdates)
            }
        }
    }

    fun appendSandboxChar(ch: String) {
        _sandboxText.value += ch
    }

    fun backspaceSandbox() {
        val current = _sandboxText.value
        if (current.isNotEmpty()) {
            _sandboxText.value = current.dropLast(1)
        }
    }

    fun setSandboxText(text: String) {
        _sandboxText.value = text
    }

    fun sendSandboxMessage() {
        val text = _sandboxText.value.trim()
        if (text.isEmpty()) return
        val isEncrypted = cryptoEngine.isCipherPayload(text)
        val mode = when {
            text.startsWith("CKH1:") -> CipherMode.HYPER
            text.startsWith("CKM1:") -> CipherMode.STANDARD
            else -> null
        }
        val msg = SandboxMessage(
            id = "${System.currentTimeMillis()}_${_sandboxMessages.value.size}",
            content = text,
            isEncrypted = isEncrypted,
            cipherMode = mode,
            timestampLabel = "Just now"
        )
        _sandboxMessages.value = _sandboxMessages.value + msg
        _sandboxText.value = ""
    }

    fun loadSandboxMessageIntoField(msg: SandboxMessage) {
        _sandboxText.value = msg.content
        _keyboardBanner.value = KeyboardStatusBanner(
            if (msg.isEncrypted) "Loaded ciphertext into field — tap Unlock to decrypt."
            else "Loaded message into field."
        )
    }

    fun lockAndEncryptSandbox() {
        val raw = _sandboxText.value.trim()
        if (raw.isEmpty()) {
            _keyboardBanner.value = KeyboardStatusBanner("Type a message first before locking.", isError = true)
            return
        }
        if (cryptoEngine.isCipherPayload(raw)) {
            _keyboardBanner.value = KeyboardStatusBanner("Text is already encrypted.")
            return
        }
        val prefs = preferences.value
        val mode = _sessionCipherMode.value ?: if (prefs.hyperEncryptionDefault) {
            CipherMode.HYPER
        } else {
            CipherMode.STANDARD
        }

        viewModelScope.launch {
            _isBusy.value = true
            try {
                val prepared = if (prefs.translationDirection != TranslationDirection.OFF) {
                    translationEngine.translate(raw, prefs.translationDirection, isOnline.value)
                        .getOrElse { raw }
                } else {
                    raw
                }
                vaultRepository.encryptTextWithActiveKey(prepared, prefs.activeRecipientKeyId, mode)
                    .onSuccess { cipher ->
                        _sandboxText.value = cipher
                        val tag = if (mode == CipherMode.HYPER) "Hyper AES-256 Encrypted" else "Encrypted"
                        _keyboardBanner.value = KeyboardStatusBanner("$tag inside text field.")
                    }
                    .onFailure {
                        _keyboardBanner.value = KeyboardStatusBanner(it.message ?: "Encryption failed", isError = true)
                    }
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun unlockAndDecryptSandbox(clipboardText: String = "") {
        val raw = _sandboxText.value.trim()
        val candidate = if (cryptoEngine.isCipherPayload(raw)) {
            raw
        } else {
            clipboardText.trim().takeIf { cryptoEngine.isCipherPayload(it) } ?: raw
        }
        if (candidate.isEmpty()) {
            _keyboardBanner.value = KeyboardStatusBanner("No encrypted CK payload in field or clipboard.", isError = true)
            return
        }
        val prefs = preferences.value
        viewModelScope.launch {
            _isBusy.value = true
            try {
                vaultRepository.decryptTextFromVault(candidate)
                    .onSuccess { (payload, senderTag) ->
                        val out = if (prefs.translationDirection != TranslationDirection.OFF) {
                            translationEngine.translate(payload.plaintext, prefs.translationDirection, isOnline.value)
                                .getOrElse { payload.plaintext }
                        } else {
                            payload.plaintext
                        }
                        _sandboxText.value = out
                        val modeLabel = if (payload.cipherMode == CipherMode.HYPER) "HYPER" else "STD"
                        _keyboardBanner.value = KeyboardStatusBanner("Unlocked ($senderTag • $modeLabel): $out")
                    }
                    .onFailure {
                        _keyboardBanner.value = KeyboardStatusBanner(it.message ?: "Decryption failed", isError = true)
                    }
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun pasteAndDecryptSandbox(clipboardText: String) {
        if (clipboardText.isBlank()) {
            _keyboardBanner.value = KeyboardStatusBanner("Clipboard is empty.", isError = true)
            return
        }
        if (!cryptoEngine.isCipherPayload(clipboardText)) {
            _sandboxText.value += clipboardText
            _keyboardBanner.value = KeyboardStatusBanner("Pasted clipboard text.")
            return
        }
        _sandboxText.value = clipboardText
        unlockAndDecryptSandbox(clipboardText)
    }

    fun translateSandboxNow() {
        val dir = preferences.value.translationDirection
        if (dir == TranslationDirection.OFF) return
        val raw = _sandboxText.value.trim()
        if (raw.isEmpty()) {
            _keyboardBanner.value = KeyboardStatusBanner("Type text to translate.", isError = true)
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            try {
                translationEngine.translate(raw, dir, isOnline.value)
                    .onSuccess { translated ->
                        _sandboxText.value = translated
                        _keyboardBanner.value = KeyboardStatusBanner("Translated (${dir.label})")
                    }
                    .onFailure {
                        _keyboardBanner.value = KeyboardStatusBanner(it.message ?: "Translation failed", isError = true)
                    }
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun toggleSessionCipherMode() {
        val current = _sessionCipherMode.value ?: if (preferences.value.hyperEncryptionDefault) {
            CipherMode.HYPER
        } else {
            CipherMode.STANDARD
        }
        _sessionCipherMode.value = if (current == CipherMode.HYPER) CipherMode.STANDARD else CipherMode.HYPER
    }

    fun cycleTranslationDirection() {
        val next = when (preferences.value.translationDirection) {
            TranslationDirection.OFF -> TranslationDirection.EN_TO_ES
            TranslationDirection.EN_TO_ES -> TranslationDirection.ES_TO_EN
            TranslationDirection.ES_TO_EN -> TranslationDirection.OFF
        }
        viewModelScope.launch {
            prefsRepo.setTranslationDirection(next)
            vaultRepository.syncPreferencesToCloud(currentUserId)
        }
    }

    fun selectActiveRecipientKey(keyId: String) {
        viewModelScope.launch {
            prefsRepo.setActiveRecipientKeyId(keyId)
        }
    }

    fun importPeerKey(token: String, tag: String, mode: CipherMode) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                vaultRepository.addOrUpdatePeerKey(currentUserId, token, tag, mode)
                    .onSuccess {
                        _keyboardBanner.value = KeyboardStatusBanner("Saved ${it.keyTag} (${it.fingerprint}) to Key Vault.")
                    }
                    .onFailure {
                        _keyboardBanner.value = KeyboardStatusBanner(it.message ?: "Invalid key token.", isError = true)
                    }
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun deletePeerKey(keyId: String) {
        viewModelScope.launch {
            vaultRepository.deleteVaultKey(currentUserId, keyId)
                .onFailure {
                    _keyboardBanner.value = KeyboardStatusBanner(it.message ?: "Failed to remove key.", isError = true)
                }
        }
    }

    fun regenerateOwnIdentity() {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                vaultRepository.regenerateOwnIdentity(currentUserId)
                    .onSuccess {
                        _keyboardBanner.value = KeyboardStatusBanner("New identity key (${it.fingerprint}) generated & encrypted.")
                    }
                    .onFailure {
                        _keyboardBanner.value = KeyboardStatusBanner(it.message ?: "Failed to regenerate identity.", isError = true)
                    }
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun syncVaultNow() {
        viewModelScope.launch {
            vaultRepository.syncPendingChanges(currentUserId)
        }
    }

    fun setHyperDefault(enabled: Boolean) {
        _sessionCipherMode.value = null
        viewModelScope.launch {
            prefsRepo.setHyperEncryptionDefault(enabled)
            vaultRepository.syncPreferencesToCloud(currentUserId)
        }
    }

    fun setNumberRowVisible(visible: Boolean) {
        viewModelScope.launch {
            prefsRepo.setNumberRowVisible(visible)
            vaultRepository.syncPreferencesToCloud(currentUserId)
        }
    }

    fun setHapticEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepo.setHapticFeedbackEnabled(enabled)
            vaultRepository.syncPreferencesToCloud(currentUserId)
        }
    }

    fun setAutoCheckUpdates(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepo.setAutoCheckUpdates(enabled)
            updateManager.scheduleBackgroundChecks(enabled)
            vaultRepository.syncPreferencesToCloud(currentUserId)
        }
    }

    fun checkUpdatesNow() {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                updateManager.checkForUpdates()
                    .onSuccess { outcome ->
                        _updateStatusMessage.value = outcome.statusMessage
                    }
                    .onFailure {
                        _updateStatusMessage.value = "Update check failed: ${it.localizedMessage ?: "Offline"}"
                    }
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun downloadOfflineTranslationPack() {
        viewModelScope.launch {
            _isBusy.value = true
            _offlinePackMessage.value = "Downloading offline EN ↔ ES neural pack..."
            try {
                translationEngine.downloadOfflinePacks()
                    .onSuccess {
                        _offlinePackMessage.value = "Offline EN ↔ ES translation pack is ready."
                    }
                    .onFailure {
                        _offlinePackMessage.value = "Download failed: ${it.localizedMessage ?: "Check connection"}"
                    }
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun showBanner(message: String, isError: Boolean = false) {
        _keyboardBanner.value = KeyboardStatusBanner(message, isError)
    }

    fun dismissBanner() {
        _keyboardBanner.value = null
    }

    private companion object {
        const val TAG = "CipherVaultVM"
    }
}
