package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.crypto.CipherMode
import com.example.data.model.DecryptedVaultKey
import com.example.data.model.TranslationDirection
import com.example.data.repository.SyncState
import com.example.features.VoiceInputController
import com.example.features.VoiceInputState
import com.example.ui.CipherVaultViewModel
import com.example.ui.SandboxMessage
import com.example.ui.UiState
import com.example.ui.keyboard.CipherKeyboardView
import com.example.ui.theme.AmberHyper
import com.example.ui.theme.EmeraldCipher
import com.example.ui.theme.JetBrainsMonoFontFamily

@Composable
fun KeyboardStudioScreen(
    viewModel: CipherVaultViewModel,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val keysState by viewModel.vaultKeysState.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val sandboxText by viewModel.sandboxText.collectAsStateWithLifecycle()
    val sandboxMessages by viewModel.sandboxMessages.collectAsStateWithLifecycle()
    val banner by viewModel.keyboardBanner.collectAsStateWithLifecycle()
    val sessionMode by viewModel.sessionCipherMode.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    val voiceController = remember(context) { VoiceInputController(context) }
    val voiceState by voiceController.state.collectAsStateWithLifecycle()

    DisposableEffect(voiceController) {
        onDispose { voiceController.stopListening() }
    }

    var isImeEnabled by remember { mutableStateOf(checkImeEnabled(context)) }
    var isImeSelected by remember { mutableStateOf(checkImeSelected(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isImeEnabled = checkImeEnabled(context)
                isImeSelected = checkImeSelected(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val lang = if (prefs.translationDirection == TranslationDirection.ES_TO_EN) "es-ES" else "en-US"
            voiceController.startListening(lang) { spoken ->
                val prefix = if (sandboxText.isBlank() || sandboxText.endsWith(" ")) "" else " "
                viewModel.setSandboxText("$sandboxText$prefix$spoken")
            }
        } else {
            viewModel.showBanner("Microphone permission is needed for hands-free voice input.", isError = true)
        }
    }

    LaunchedEffect(voiceState) {
        if (voiceState is VoiceInputState.Error) {
            viewModel.showBanner((voiceState as VoiceInputState.Error).message, isError = true)
            voiceController.clearError()
        }
    }

    val keys: List<DecryptedVaultKey> = when (val state = keysState) {
        is UiState.Success -> state.data
        else -> emptyList()
    }
    val activeMode = sessionMode ?: if (prefs.hyperEncryptionDefault) {
        CipherMode.HYPER
    } else {
        CipherMode.STANDARD
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Status + System Keyboard Activation Strip
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "CipherKey Studio",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Test Lock, Unlock, Hyper & Translation live",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ConnectivityBadge(isOnline = isOnline, syncState = syncState)
            }

            // System IME Activation Banner
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = if (isImeSelected) EmeraldCipher.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(14.dp)
                    )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = if (isImeSelected) Icons.Filled.CheckCircle else Icons.Filled.Keyboard,
                        contentDescription = null,
                        tint = if (isImeSelected) EmeraldCipher else AmberHyper,
                        modifier = Modifier.size(22.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                isImeSelected -> "CipherKey System Keyboard Active"
                                isImeEnabled -> "CipherKey Enabled — Select as Default"
                                else -> "Enable CipherKey System Keyboard"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (isImeSelected) {
                                "Ready across WhatsApp, Messages, Signal & all apps"
                            } else {
                                "Use Lock/Unlock inside any Android messaging app"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (!isImeEnabled) {
                        OutlinedButton(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("enable_ime_button")
                        ) {
                            Text("Enable", fontSize = 12.sp)
                        }
                    } else if (!isImeSelected) {
                        OutlinedButton(
                            onClick = {
                                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                imm.showInputMethodPicker()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = EmeraldCipher),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("select_ime_button")
                        ) {
                            Text("Select", fontSize = 12.sp)
                        }
                    }
                }
            }

            // Live Messaging Sandbox Thread
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (sandboxMessages.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = null,
                                tint = EmeraldCipher.copy(alpha = 0.7f),
                                modifier = Modifier.size(30.dp)
                            )
                            Text(
                                text = "Encrypted Message Sandbox",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Type below with the Gboard-style keyboard, tap Lock to encrypt in-place, press Send, then tap any encrypted bubble to load & Unlock.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(sandboxMessages, key = { it.id }) { msg ->
                            SandboxMessageBubble(
                                msg = msg,
                                onLoadIntoField = { viewModel.loadSandboxMessageIntoField(msg) }
                            )
                        }
                    }
                }
            }

            // Live Input Field
            val isCipherText = sandboxText.trimStart().startsWith("CKM1:") ||
                sandboxText.trimStart().startsWith("CKH1:")
            OutlinedTextField(
                value = sandboxText,
                onValueChange = { viewModel.setSandboxText(it) },
                placeholder = {
                    Text("Type a secret message or paste CK ciphertext...")
                },
                textStyle = TextStyle(
                    fontFamily = if (isCipherText) JetBrainsMonoFontFamily else MaterialTheme.typography.bodyLarge.fontFamily,
                    fontSize = if (isCipherText) 12.sp else 15.sp,
                    color = if (isCipherText) EmeraldCipher else MaterialTheme.colorScheme.onSurface
                ),
                maxLines = 3,
                trailingIcon = {
                    Row {
                        if (sandboxText.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("CipherKey Message", sandboxText))
                                    viewModel.showBanner("Copied field text to clipboard.")
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ContentCopy,
                                    contentDescription = "Copy text",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(onClick = { viewModel.setSandboxText("") }) {
                                Icon(
                                    imageVector = Icons.Filled.Clear,
                                    contentDescription = "Clear text",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("studio_message_input")
            )
        }

        // Embedded Gboard-style CipherKey Keyboard
        CipherKeyboardView(
            keys = keys,
            activeRecipientKeyId = prefs.activeRecipientKeyId,
            cipherMode = activeMode,
            translationDirection = prefs.translationDirection,
            numberRowVisible = prefs.numberRowVisible,
            hapticEnabled = prefs.hapticFeedbackEnabled,
            voiceState = voiceState,
            statusBanner = banner,
            isBusy = isBusy,
            useSendIconForEnter = true,
            applyBottomNavInsets = false,
            onChar = { viewModel.appendSandboxChar(it) },
            onBackspace = { viewModel.backspaceSandbox() },
            onEnter = { viewModel.sendSandboxMessage() },
            onLockEncrypt = { viewModel.lockAndEncryptSandbox() },
            onUnlockDecrypt = {
                viewModel.unlockAndDecryptSandbox(readClipboard(context))
            },
            onPasteAndDecrypt = {
                viewModel.pasteAndDecryptSandbox(readClipboard(context))
            },
            onSelectRecipientKey = { keyId ->
                viewModel.selectActiveRecipientKey(keyId)
                val chosen = keys.firstOrNull { it.id == keyId }
                viewModel.showBanner("Active target key: ${chosen?.keyTag ?: keyId}")
            },
            onQuickImportKey = { token, tag ->
                viewModel.importPeerKey(token, tag, activeMode)
            },
            onCopyOwnKey = {
                val own = keys.firstOrNull { it.ownIdentity }
                if (own != null) {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("CipherKey Public Key", own.publicKeyToken))
                    viewModel.showBanner("Copied your public key (${own.fingerprint})")
                }
            },
            onToggleCipherMode = { viewModel.toggleSessionCipherMode() },
            onCycleTranslation = { viewModel.cycleTranslationDirection() },
            onTranslateNow = { viewModel.translateSandboxNow() },
            onVoiceClick = {
                if (voiceController.state.value is VoiceInputState.Listening) {
                    voiceController.stopListening()
                } else {
                    val hasPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasPermission) {
                        val lang = if (prefs.translationDirection == TranslationDirection.ES_TO_EN) "es-ES" else "en-US"
                        voiceController.startListening(lang) { spoken ->
                            val prefix = if (sandboxText.isBlank() || sandboxText.endsWith(" ")) "" else " "
                            viewModel.setSandboxText("$sandboxText$prefix$spoken")
                        }
                    } else {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            },
            onSwitchIme = {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            },
            onOpenSettings = onOpenSettings,
            onDismissBanner = { viewModel.dismissBanner() },
            modifier = Modifier.widthIn(max = 640.dp)
        )
    }
}

@Composable
fun ConnectivityBadge(isOnline: Boolean, syncState: SyncState) {
    val label = when {
        !isOnline || syncState is SyncState.OfflineLocalOnly -> "Offline Vault"
        syncState is SyncState.Syncing -> "Syncing..."
        syncState is SyncState.Error -> "Sync Retry"
        else -> "Vault Synced"
    }
    val tint = when {
        !isOnline || syncState is SyncState.OfflineLocalOnly -> AmberHyper
        syncState is SyncState.Error -> MaterialTheme.colorScheme.error
        else -> EmeraldCipher
    }
    Surface(
        color = tint.copy(alpha = 0.14f),
        shape = CircleShape,
        modifier = Modifier.border(1.dp, tint.copy(alpha = 0.5f), CircleShape)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = when {
                    !isOnline -> Icons.Filled.CloudOff
                    syncState is SyncState.Syncing -> Icons.Filled.Sync
                    else -> Icons.Filled.CloudDone
                },
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun SandboxMessageBubble(
    msg: SandboxMessage,
    onLoadIntoField: () -> Unit
) {
    Surface(
        color = if (msg.isEncrypted) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onLoadIntoField)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = if (msg.isEncrypted) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        contentDescription = null,
                        tint = if (msg.isEncrypted) EmeraldCipher else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = when {
                            msg.cipherMode == CipherMode.HYPER -> "HYPER ENCRYPTED"
                            msg.isEncrypted -> "E2E ENCRYPTED"
                            else -> "PLAINTEXT"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (msg.isEncrypted) EmeraldCipher else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                    if (msg.cipherMode == CipherMode.HYPER) {
                        Icon(
                            imageVector = Icons.Filled.Bolt,
                            contentDescription = null,
                            tint = AmberHyper,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
                TextButton(
                    onClick = onLoadIntoField,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(24.dp)
                ) {
                    Text(
                        text = if (msg.isEncrypted) "Load & Unlock" else "Load",
                        fontSize = 11.sp
                    )
                }
            }
            Text(
                text = msg.content,
                style = if (msg.isEncrypted) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun readClipboard(context: Context): String {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
    return cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
}

private fun checkImeEnabled(context: Context): Boolean {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
    return imm.enabledInputMethodList.any { it.packageName == context.packageName }
}

private fun checkImeSelected(context: Context): Boolean {
    val currentIme = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.DEFAULT_INPUT_METHOD
    ) ?: return false
    return currentIme.startsWith(context.packageName)
}
