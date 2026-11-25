package com.example.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.CipherKeyApplication
import com.example.MainActivity
import com.example.crypto.CipherMode
import com.example.data.local.KeyboardPreferences
import com.example.data.model.DecryptedVaultKey
import com.example.data.model.TranslationDirection
import com.example.features.VoiceInputController
import com.example.features.VoiceInputState
import com.example.ui.keyboard.CipherKeyboardView
import com.example.ui.keyboard.KeyboardStatusBanner
import com.example.ui.theme.CipherKeyTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CipherInputMethodService :
    InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var voiceController: VoiceInputController
    private val keysState = MutableStateFlow<List<DecryptedVaultKey>>(emptyList())
    private val prefsState = MutableStateFlow(KeyboardPreferences())
    private var statusBanner by mutableStateOf<KeyboardStatusBanner?>(null)
    private var isBusy by mutableStateOf(false)
    private var sessionCipherMode by mutableStateOf<CipherMode?>(null)
    private var useSendIcon by mutableStateOf(true)

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        voiceController = VoiceInputController(this)

        val container = (application as CipherKeyApplication).container
        serviceScope.launch {
            container.preferencesRepository.preferences.collectLatest { prefs ->
                prefsState.value = prefs
            }
        }
        serviceScope.launch {
            val uid = Firebase.auth.currentUser?.uid ?: "local_offline_user"
            container.vaultRepository.observeLocalVaultKeys(uid).collectLatest { list ->
                keysState.value = list
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        useSendIcon = action == EditorInfo.IME_ACTION_SEND ||
            action == EditorInfo.IME_ACTION_GO ||
            action == EditorInfo.IME_ACTION_SEARCH

        val uid = Firebase.auth.currentUser?.uid ?: return
        val container = (application as CipherKeyApplication).container
        serviceScope.launch {
            keysState.value = container.vaultRepository.getKeysSnapshotForIme()
            container.vaultRepository.ensureOwnIdentity(uid)
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        voiceController.stopListening()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    override fun onCreateInputView(): View {
        val decorView = window?.window?.decorView
        decorView?.let {
            it.setViewTreeLifecycleOwner(this)
            it.setViewTreeViewModelStoreOwner(this)
            it.setViewTreeSavedStateRegistryOwner(this)
        }

        val container = (application as CipherKeyApplication).container
        return ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val keys by keysState.collectAsStateWithLifecycle()
                val prefs by prefsState.collectAsStateWithLifecycle()
                val voiceState by voiceController.state.collectAsStateWithLifecycle()
                val isOnline by container.vaultRepository.isOnline.collectAsStateWithLifecycle()
                val activeMode = sessionCipherMode ?: if (prefs.hyperEncryptionDefault) {
                    CipherMode.HYPER
                } else {
                    CipherMode.STANDARD
                }

                CipherKeyTheme {
                    CipherKeyboardView(
                        keys = keys,
                        activeRecipientKeyId = prefs.activeRecipientKeyId,
                        cipherMode = activeMode,
                        translationDirection = prefs.translationDirection,
                        numberRowVisible = prefs.numberRowVisible,
                        hapticEnabled = prefs.hapticFeedbackEnabled,
                        voiceState = voiceState,
                        statusBanner = statusBanner,
                        isBusy = isBusy,
                        useSendIconForEnter = useSendIcon,
                        applyBottomNavInsets = true,
                        onChar = { text ->
                            currentInputConnection?.commitText(text, 1)
                        },
                        onBackspace = {
                            val ic = currentInputConnection ?: return@CipherKeyboardView
                            val selected = ic.getSelectedText(0)
                            if (!selected.isNullOrEmpty()) {
                                ic.commitText("", 1)
                            } else {
                                ic.deleteSurroundingText(1, 0)
                            }
                        },
                        onEnter = {
                            val ic = currentInputConnection ?: return@CipherKeyboardView
                            val action = (currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
                            if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                                ic.performEditorAction(action)
                            } else {
                                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                            }
                        },
                        onLockEncrypt = {
                            serviceScope.launch {
                                encryptCurrentInputField(activeMode, prefs.translationDirection, prefs.activeRecipientKeyId, isOnline)
                            }
                        },
                        onUnlockDecrypt = {
                            serviceScope.launch {
                                decryptCurrentInputField(prefs.translationDirection, isOnline)
                            }
                        },
                        onPasteAndDecrypt = {
                            serviceScope.launch {
                                pasteAndDecryptFromClipboard(prefs.translationDirection, isOnline)
                            }
                        },
                        onSelectRecipientKey = { keyId ->
                            serviceScope.launch {
                                container.preferencesRepository.setActiveRecipientKeyId(keyId)
                                val chosen = keys.firstOrNull { it.id == keyId }
                                statusBanner = KeyboardStatusBanner("Target key: ${chosen?.keyTag ?: keyId}")
                            }
                        },
                        onQuickImportKey = { token, tag ->
                            serviceScope.launch {
                                val uid = Firebase.auth.currentUser?.uid ?: "local_offline_user"
                                container.vaultRepository.addOrUpdatePeerKey(uid, token, tag, activeMode)
                                    .onSuccess {
                                        statusBanner = KeyboardStatusBanner("Saved ${it.keyTag} (${it.fingerprint}) to Vault")
                                    }
                                    .onFailure {
                                        statusBanner = KeyboardStatusBanner(it.message ?: "Invalid key", isError = true)
                                    }
                            }
                        },
                        onCopyOwnKey = {
                            val own = keys.firstOrNull { it.ownIdentity }
                            if (own != null) {
                                copyToClipboard("CipherKey Public Key", own.publicKeyToken)
                                statusBanner = KeyboardStatusBanner("Copied your public key (${own.fingerprint})")
                            }
                        },
                        onToggleCipherMode = {
                            sessionCipherMode = if (activeMode == CipherMode.HYPER) {
                                CipherMode.STANDARD
                            } else {
                                CipherMode.HYPER
                            }
                        },
                        onCycleTranslation = {
                            serviceScope.launch {
                                val next = when (prefs.translationDirection) {
                                    TranslationDirection.OFF -> TranslationDirection.EN_TO_ES
                                    TranslationDirection.EN_TO_ES -> TranslationDirection.ES_TO_EN
                                    TranslationDirection.ES_TO_EN -> TranslationDirection.OFF
                                }
                                container.preferencesRepository.setTranslationDirection(next)
                            }
                        },
                        onTranslateNow = {
                            serviceScope.launch {
                                translateCurrentField(prefs.translationDirection, isOnline)
                            }
                        },
                        onVoiceClick = {
                            if (voiceController.state.value is VoiceInputState.Listening) {
                                voiceController.stopListening()
                            } else {
                                val lang = if (prefs.translationDirection == TranslationDirection.ES_TO_EN) "es-ES" else "en-US"
                                voiceController.startListening(lang) { spoken ->
                                    currentInputConnection?.commitText("$spoken ", 1)
                                }
                            }
                        },
                        onSwitchIme = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                switchToNextInputMethod(false)
                            } else {
                                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                imm.showInputMethodPicker()
                            }
                        },
                        onOpenSettings = {
                            val intent = Intent(this@CipherInputMethodService, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(intent)
                        },
                        onDismissBanner = { statusBanner = null }
                    )
                }
            }
        }
    }

    private fun readFieldText(): String {
        val ic = currentInputConnection ?: return ""
        val selected = ic.getSelectedText(0)?.toString()
        if (!selected.isNullOrBlank()) return selected
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()
        if (!extracted.isNullOrEmpty()) return extracted
        val before = ic.getTextBeforeCursor(4000, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(4000, 0)?.toString().orEmpty()
        return before + after
    }

    private fun replaceFieldText(newText: String) {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText(newText, 1)
            return
        }
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)?.text
        if (extracted != null) {
            ic.beginBatchEdit()
            ic.setSelection(0, extracted.length)
            ic.commitText(newText, 1)
            ic.endBatchEdit()
        } else {
            val beforeLen = ic.getTextBeforeCursor(4000, 0)?.length ?: 0
            val afterLen = ic.getTextAfterCursor(4000, 0)?.length ?: 0
            ic.beginBatchEdit()
            ic.deleteSurroundingText(beforeLen, afterLen)
            ic.commitText(newText, 1)
            ic.endBatchEdit()
        }
    }

    private suspend fun encryptCurrentInputField(
        mode: CipherMode,
        translationDirection: TranslationDirection,
        recipientKeyId: String,
        isOnline: Boolean
    ) {
        val raw = readFieldText().trim()
        if (raw.isEmpty()) {
            statusBanner = KeyboardStatusBanner("Type a message first before locking.", isError = true)
            return
        }
        val container = (application as CipherKeyApplication).container
        if (container.cryptoEngine.isCipherPayload(raw)) {
            statusBanner = KeyboardStatusBanner("Message is already encrypted.")
            return
        }
        isBusy = true
        try {
            val prepared = if (translationDirection != TranslationDirection.OFF) {
                container.translationEngine.translate(raw, translationDirection, isOnline)
                    .getOrElse { raw }
            } else {
                raw
            }
            container.vaultRepository.encryptTextWithActiveKey(prepared, recipientKeyId, mode)
                .onSuccess { cipher ->
                    replaceFieldText(cipher)
                    val label = if (mode == CipherMode.HYPER) "Hyper Encrypted" else "Encrypted"
                    statusBanner = KeyboardStatusBanner("$label in field. Ready to send.")
                }
                .onFailure {
                    statusBanner = KeyboardStatusBanner(it.message ?: "Encryption failed", isError = true)
                }
        } finally {
            isBusy = false
        }
    }

    private suspend fun decryptCurrentInputField(
        translationDirection: TranslationDirection,
        isOnline: Boolean
    ) {
        val container = (application as CipherKeyApplication).container
        val fieldText = readFieldText().trim()
        val candidate = if (container.cryptoEngine.isCipherPayload(fieldText)) {
            fieldText
        } else {
            readClipboardText().takeIf { container.cryptoEngine.isCipherPayload(it) } ?: fieldText
        }

        if (candidate.isBlank()) {
            statusBanner = KeyboardStatusBanner("Select or copy an encrypted CK message to unlock.", isError = true)
            return
        }

        isBusy = true
        try {
            container.vaultRepository.decryptTextFromVault(candidate)
                .onSuccess { (payload, senderTag) ->
                    val displayPlain = if (translationDirection != TranslationDirection.OFF) {
                        container.translationEngine.translate(payload.plaintext, translationDirection, isOnline)
                            .getOrElse { payload.plaintext }
                    } else {
                        payload.plaintext
                    }
                    replaceFieldText(displayPlain)
                    val modeBadge = if (payload.cipherMode == CipherMode.HYPER) "HYPER" else "STD"
                    statusBanner = KeyboardStatusBanner("Unlocked ($senderTag • $modeBadge): $displayPlain")
                }
                .onFailure {
                    statusBanner = KeyboardStatusBanner(it.message ?: "Unlock failed", isError = true)
                }
        } finally {
            isBusy = false
        }
    }

    private suspend fun pasteAndDecryptFromClipboard(
        translationDirection: TranslationDirection,
        isOnline: Boolean
    ) {
        val clip = readClipboardText()
        if (clip.isBlank()) {
            statusBanner = KeyboardStatusBanner("Clipboard is empty.", isError = true)
            return
        }
        val container = (application as CipherKeyApplication).container
        if (!container.cryptoEngine.isCipherPayload(clip)) {
            currentInputConnection?.commitText(clip, 1)
            statusBanner = KeyboardStatusBanner("Pasted clipboard text.")
            return
        }
        isBusy = true
        try {
            container.vaultRepository.decryptTextFromVault(clip)
                .onSuccess { (payload, senderTag) ->
                    val out = if (translationDirection != TranslationDirection.OFF) {
                        container.translationEngine.translate(payload.plaintext, translationDirection, isOnline)
                            .getOrElse { payload.plaintext }
                    } else {
                        payload.plaintext
                    }
                    replaceFieldText(out)
                    statusBanner = KeyboardStatusBanner("Decrypted from $senderTag: $out")
                }
                .onFailure {
                    statusBanner = KeyboardStatusBanner(it.message ?: "Cannot decrypt clipboard", isError = true)
                }
        } finally {
            isBusy = false
        }
    }

    private suspend fun translateCurrentField(direction: TranslationDirection, isOnline: Boolean) {
        if (direction == TranslationDirection.OFF) return
        val text = readFieldText().trim()
        if (text.isEmpty()) {
            statusBanner = KeyboardStatusBanner("Type text to translate.", isError = true)
            return
        }
        val container = (application as CipherKeyApplication).container
        isBusy = true
        try {
            container.translationEngine.translate(text, direction, isOnline)
                .onSuccess { translated ->
                    replaceFieldText(translated)
                    statusBanner = KeyboardStatusBanner("Translated (${direction.label})")
                }
                .onFailure {
                    statusBanner = KeyboardStatusBanner(it.message ?: "Translation failed", isError = true)
                }
        } finally {
            isBusy = false
        }
    }

    private fun readClipboardText(): String {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
        return cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceController.stopListening()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
        serviceScope.cancel()
    }
}
