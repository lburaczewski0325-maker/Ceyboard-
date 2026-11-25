package com.example.ui.keyboard

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.KeyboardCapslock
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.crypto.CipherMode
import com.example.data.model.DecryptedVaultKey
import com.example.data.model.TranslationDirection
import com.example.features.VoiceInputState
import com.example.ui.theme.AmberHyper
import com.example.ui.theme.EmeraldCipher
import com.example.ui.theme.EmeraldOnPrimary
import com.example.ui.theme.GboardDarkBg
import com.example.ui.theme.GboardDarkKey
import com.example.ui.theme.GboardDarkKeyAction
import com.example.ui.theme.GboardLightBg
import com.example.ui.theme.GboardLightKey
import com.example.ui.theme.GboardLightKeyAction
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class ShiftState {
    OFF,
    SHIFT,
    CAPS_LOCK
}

enum class KeyboardPage {
    LETTERS,
    SYMBOLS_1,
    SYMBOLS_2
}

data class KeyboardStatusBanner(
    val message: String,
    val isError: Boolean = false
)

@Composable
fun CipherKeyboardView(
    keys: List<DecryptedVaultKey>,
    activeRecipientKeyId: String,
    cipherMode: CipherMode,
    translationDirection: TranslationDirection,
    numberRowVisible: Boolean,
    hapticEnabled: Boolean,
    voiceState: VoiceInputState,
    statusBanner: KeyboardStatusBanner?,
    isBusy: Boolean = false,
    useSendIconForEnter: Boolean = true,
    applyBottomNavInsets: Boolean = true,
    onChar: (String) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onLockEncrypt: () -> Unit,
    onUnlockDecrypt: () -> Unit,
    onPasteAndDecrypt: () -> Unit,
    onSelectRecipientKey: (String) -> Unit,
    onQuickImportKey: (token: String, tag: String) -> Unit,
    onCopyOwnKey: () -> Unit,
    onToggleCipherMode: () -> Unit,
    onCycleTranslation: () -> Unit,
    onTranslateNow: () -> Unit,
    onVoiceClick: () -> Unit,
    onSwitchIme: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    onDismissBanner: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val bgColor = if (isDark) GboardDarkBg else GboardLightBg
    val keyColor = if (isDark) GboardDarkKey else GboardLightKey
    val actionKeyColor = if (isDark) GboardDarkKeyAction else GboardLightKeyAction
    val keyTextColor = if (isDark) Color(0xFFE8EAED) else Color(0xFF1F2328)
    val mutedColor = if (isDark) Color(0xFF9AA0A6) else Color(0xFF5F6368)

    var shiftState by remember { mutableStateOf(ShiftState.SHIFT) }
    var page by remember { mutableStateOf(KeyboardPage.LETTERS) }
    var showKeyBankDrawer by remember { mutableStateOf(false) }
    var showInlineAddKey by remember { mutableStateOf(false) }
    var quickKeyToken by remember { mutableStateOf("") }
    var quickKeyTag by remember { mutableStateOf("") }
    var lastShiftTapMillis by remember { mutableStateOf(0L) }

    val view = LocalView.current
    val scope = rememberCoroutineScope()

    fun triggerHaptic() {
        if (hapticEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    val peerKeys = remember(keys) { keys.filter { !it.ownIdentity } }
    val ownKey = remember(keys) { keys.firstOrNull { it.ownIdentity } }
    val activeKey = remember(keys, activeRecipientKeyId) {
        peerKeys.firstOrNull { it.id == activeRecipientKeyId }
            ?: peerKeys.firstOrNull()
            ?: ownKey
    }

    val baseModifier = modifier
        .fillMaxWidth()
        .background(bgColor)
        .let { if (applyBottomNavInsets) it.navigationBarsPadding() else it }

    Column(
        modifier = baseModifier.padding(top = 6.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        // 1. ABOVE-KEYBOARD CIPHER BAR (Lock, Unlock, Key Bank, Hyper)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // LOCK (ENCRYPT) BUTTON
            Surface(
                onClick = {
                    triggerHaptic()
                    onLockEncrypt()
                },
                enabled = !isBusy,
                color = EmeraldCipher,
                contentColor = EmeraldOnPrimary,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1.1f)
                    .height(40.dp)
                    .testTag("keyboard_lock_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = "Lock and encrypt message",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "Lock",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            // UNLOCK (DECRYPT) BUTTON
            Surface(
                onClick = {
                    triggerHaptic()
                    onUnlockDecrypt()
                },
                enabled = !isBusy,
                color = if (isDark) Color(0xFF28303A) else Color(0xFFE0E7EF),
                contentColor = keyTextColor,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1.15f)
                    .height(40.dp)
                    .border(
                        width = 1.dp,
                        color = AmberHyper.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(10.dp)
                    )
                    .testTag("keyboard_unlock_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.LockOpen,
                        contentDescription = "Unlock and decrypt message",
                        tint = AmberHyper,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "Unlock",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }

            // KEY BANK BUTTON (No contacts needed, direct alphanumeric key switcher)
            Surface(
                onClick = {
                    triggerHaptic()
                    showKeyBankDrawer = !showKeyBankDrawer
                },
                color = if (showKeyBankDrawer) MaterialTheme.colorScheme.primaryContainer else actionKeyColor,
                contentColor = if (showKeyBankDrawer) MaterialTheme.colorScheme.onPrimaryContainer else keyTextColor,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1.45f)
                    .height(40.dp)
                    .testTag("keyboard_keybank_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Key,
                        contentDescription = "Open Key Bank",
                        tint = EmeraldCipher,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = activeKey?.keyTag ?: "Key Bank",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // HYPER ENCRYPTION TOGGLE
            val isHyper = cipherMode == CipherMode.HYPER
            Surface(
                onClick = {
                    triggerHaptic()
                    onToggleCipherMode()
                },
                color = if (isHyper) AmberHyper.copy(alpha = 0.18f) else actionKeyColor,
                contentColor = if (isHyper) AmberHyper else mutedColor,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .height(40.dp)
                    .border(
                        width = 1.dp,
                        color = if (isHyper) AmberHyper.copy(alpha = 0.7f) else Color.Transparent,
                        shape = RoundedCornerShape(10.dp)
                    )
                    .testTag("keyboard_hyper_toggle")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Bolt,
                        contentDescription = "Toggle Hyper Encryption",
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = if (isHyper) "HYPER" else "STD",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 2. INLINE KEY BANK DRAWER (No contacts needed, direct swap & select from keyboard!)
        AnimatedVisibility(visible = showKeyBankDrawer) {
            Surface(
                color = if (isDark) Color(0xFF14181D) else Color(0xFFE3E8EF),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Key Bank (${peerKeys.size} saved)",
                            style = MaterialTheme.typography.labelLarge,
                            color = keyTextColor
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (ownKey != null) {
                                TextButton(
                                    onClick = {
                                        triggerHaptic()
                                        onCopyOwnKey()
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.testTag("keyboard_copy_own_key")
                                ) {
                                    Icon(Icons.Filled.ContentCopy, null, Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("My Key (${ownKey.fingerprint})", fontSize = 11.sp)
                                }
                            }
                            TextButton(
                                onClick = { showInlineAddKey = !showInlineAddKey },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.testTag("keyboard_add_key_toggle")
                            ) {
                                Icon(Icons.Filled.Add, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(3.dp))
                                Text("Swap Key", fontSize = 11.sp)
                            }
                        }
                    }

                    if (peerKeys.isEmpty()) {
                        Text(
                            text = "No peer keys swapped yet. Tap 'Swap Key' to paste an alphanumeric key, or encrypt with your loopback key for testing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedColor
                        )
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(peerKeys, key = { it.id }) { keyItem ->
                                val selected = keyItem.id == activeKey?.id
                                Surface(
                                    onClick = {
                                        triggerHaptic()
                                        onSelectRecipientKey(keyItem.id)
                                        showKeyBankDrawer = false
                                    },
                                    color = if (selected) EmeraldCipher.copy(alpha = 0.22f) else keyColor,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.border(
                                        width = 1.dp,
                                        color = if (selected) EmeraldCipher else Color.Transparent,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                        Text(
                                            text = keyItem.keyTag,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (selected) EmeraldCipher else keyTextColor,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = keyItem.fingerprint,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = mutedColor
                                        )
                                    }
                                }
                            }
                        }
                    }

                    AnimatedVisibility(visible = showInlineAddKey) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = quickKeyToken,
                                onValueChange = { quickKeyToken = it },
                                label = { Text("Paste CK1... alphanumeric key", fontSize = 12.sp) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = quickKeyTag,
                                    onValueChange = { quickKeyTag = it },
                                    label = { Text("Optional tag", fontSize = 12.sp) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = {
                                        if (quickKeyToken.isNotBlank()) {
                                            onQuickImportKey(quickKeyToken, quickKeyTag)
                                            quickKeyToken = ""
                                            quickKeyTag = ""
                                            showInlineAddKey = false
                                            showKeyBankDrawer = false
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = EmeraldCipher,
                                        contentColor = EmeraldOnPrimary
                                    ),
                                    modifier = Modifier.testTag("keyboard_save_swapped_key")
                                ) {
                                    Text("Save")
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. STATUS / DECRYPTED RESULT BANNER
        AnimatedVisibility(visible = statusBanner != null) {
            statusBanner?.let { banner ->
                Surface(
                    color = if (banner.isError) Color(0xFF47161B) else Color(0xFF0D3B2E),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = banner.message,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (banner.isError) Color(0xFFFFB4AB) else EmeraldCipher,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(
                            onClick = onDismissBanner,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Dismiss status",
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }

        // 4. GBOARD UTILITY STRIP (Paste-Decrypt, EN <-> ES Toggle, Translate Now, Settings, Voice Mic)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .height(38.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Paste & Decrypt chip (Gboard clipboard analog)
            Surface(
                onClick = {
                    triggerHaptic()
                    onPasteAndDecrypt()
                },
                color = actionKeyColor,
                shape = CircleShape,
                modifier = Modifier
                    .size(36.dp)
                    .testTag("keyboard_paste_decrypt_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.ContentPaste,
                        contentDescription = "Paste and decrypt clipboard",
                        tint = keyTextColor,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            // EN <-> ES Translation Toggle Pill
            val transActive = translationDirection != TranslationDirection.OFF
            Surface(
                onClick = {
                    triggerHaptic()
                    onCycleTranslation()
                },
                color = if (transActive) Color(0xFF163A59) else actionKeyColor,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .height(34.dp)
                    .border(
                        width = 1.dp,
                        color = if (transActive) Color(0xFF58A6FF) else Color.Transparent,
                        shape = RoundedCornerShape(18.dp)
                    )
                    .testTag("keyboard_translate_toggle")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Translate,
                        contentDescription = "Toggle English to Spanish translation",
                        tint = if (transActive) Color(0xFF58A6FF) else mutedColor,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = if (transActive) translationDirection.label else "EN ↔ ES",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (transActive) Color(0xFF9CD1FF) else mutedColor
                    )
                }
            }

            if (transActive) {
                Surface(
                    onClick = {
                        triggerHaptic()
                        onTranslateNow()
                    },
                    color = Color(0xFF58A6FF).copy(alpha = 0.2f),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .height(34.dp)
                        .testTag("keyboard_translate_now_button")
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isBusy) "..." else "Translate",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF9CD1FF),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            if (onOpenSettings != null) {
                Surface(
                    onClick = {
                        triggerHaptic()
                        onOpenSettings()
                    },
                    color = actionKeyColor,
                    shape = CircleShape,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Keyboard settings",
                            tint = mutedColor,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }

            // GBOARD VOICE-TO-TEXT MICROPHONE BUTTON
            val isListening = voiceState is VoiceInputState.Listening
            val pulseTransition = rememberInfiniteTransition(label = "mic_pulse")
            val pulseScale by pulseTransition.animateFloat(
                initialValue = 1f,
                targetValue = if (isListening) 1.14f else 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(550),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "mic_scale"
            )
            val micBg by animateColorAsState(
                targetValue = if (isListening) EmeraldCipher else actionKeyColor,
                label = "mic_bg"
            )
            Surface(
                onClick = {
                    triggerHaptic()
                    onVoiceClick()
                },
                color = micBg,
                shape = CircleShape,
                modifier = Modifier
                    .size(36.dp)
                    .scale(pulseScale)
                    .testTag("keyboard_voice_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = if (isListening) "Stop voice input" else "Start voice to text",
                        tint = if (isListening) EmeraldOnPrimary else keyTextColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Live voice partial preview chip when listening
        if (voiceState is VoiceInputState.Listening) {
            Text(
                text = if (voiceState.partialText.isBlank()) "Listening... speak now" else "Voice: ${voiceState.partialText}",
                style = MaterialTheme.typography.labelMedium,
                color = EmeraldCipher,
                modifier = Modifier.padding(horizontal = 12.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 5. GBOARD KEYBOARD MATRIX
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (numberRowVisible && page == KeyboardPage.LETTERS) {
                KeyRow {
                    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").forEach { digit ->
                        CharacterKey(
                            label = digit,
                            keyColor = keyColor,
                            textColor = keyTextColor,
                            heightDp = 38,
                            onTap = {
                                triggerHaptic()
                                onChar(digit)
                            }
                        )
                    }
                }
            }

            when (page) {
                KeyboardPage.LETTERS -> {
                    val isUpper = shiftState != ShiftState.OFF
                    val row1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
                    val row2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
                    val row3 = listOf("z", "x", "c", "v", "b", "n", "m")

                    KeyRow {
                        row1.forEach { ch ->
                            val out = if (isUpper) ch.uppercase() else ch
                            CharacterKey(
                                label = out,
                                keyColor = keyColor,
                                textColor = keyTextColor,
                                onTap = {
                                    triggerHaptic()
                                    onChar(out)
                                    if (shiftState == ShiftState.SHIFT) shiftState = ShiftState.OFF
                                }
                            )
                        }
                    }

                    KeyRow {
                        Spacer(Modifier.weight(0.5f))
                        row2.forEach { ch ->
                            val out = if (isUpper) ch.uppercase() else ch
                            CharacterKey(
                                label = out,
                                keyColor = keyColor,
                                textColor = keyTextColor,
                                onTap = {
                                    triggerHaptic()
                                    onChar(out)
                                    if (shiftState == ShiftState.SHIFT) shiftState = ShiftState.OFF
                                }
                            )
                        }
                        Spacer(Modifier.weight(0.5f))
                    }

                    KeyRow {
                        // Shift / Caps Lock key
                        ActionKey(
                            weight = 1.5f,
                            bgColor = if (shiftState != ShiftState.OFF) keyTextColor.copy(alpha = 0.22f) else actionKeyColor,
                            onTap = {
                                triggerHaptic()
                                val now = System.currentTimeMillis()
                                shiftState = when {
                                    shiftState == ShiftState.CAPS_LOCK -> ShiftState.OFF
                                    now - lastShiftTapMillis < 350L -> ShiftState.CAPS_LOCK
                                    shiftState == ShiftState.OFF -> ShiftState.SHIFT
                                    else -> ShiftState.OFF
                                }
                                lastShiftTapMillis = now
                            },
                            testTag = "keyboard_shift_key"
                        ) {
                            Icon(
                                imageVector = if (shiftState == ShiftState.CAPS_LOCK) {
                                    Icons.Filled.KeyboardCapslock
                                } else {
                                    Icons.Filled.Upload
                                },
                                contentDescription = "Shift",
                                tint = if (shiftState == ShiftState.CAPS_LOCK) EmeraldCipher else keyTextColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        row3.forEach { ch ->
                            val out = if (isUpper) ch.uppercase() else ch
                            CharacterKey(
                                label = out,
                                keyColor = keyColor,
                                textColor = keyTextColor,
                                onTap = {
                                    triggerHaptic()
                                    onChar(out)
                                    if (shiftState == ShiftState.SHIFT) shiftState = ShiftState.OFF
                                }
                            )
                        }

                        // Repeatable Backspace key
                        Box(
                            modifier = Modifier
                                .weight(1.5f)
                                .height(46.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(actionKeyColor)
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onPress = {
                                            triggerHaptic()
                                            onBackspace()
                                            val job = scope.launch {
                                                delay(420L)
                                                while (true) {
                                                    onBackspace()
                                                    delay(55L)
                                                }
                                            }
                                            tryAwaitRelease()
                                            job.cancel()
                                        }
                                    )
                                }
                                .testTag("keyboard_backspace_key"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Backspace,
                                contentDescription = "Backspace",
                                tint = keyTextColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                KeyboardPage.SYMBOLS_1, KeyboardPage.SYMBOLS_2 -> {
                    val symRow1 = if (page == KeyboardPage.SYMBOLS_1) {
                        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
                    } else {
                        listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "Δ")
                    }
                    val symRow2 = if (page == KeyboardPage.SYMBOLS_1) {
                        listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/")
                    } else {
                        listOf("£", "¢", "€", "¥", "^", "°", "=", "{", "}", "\\")
                    }
                    val symRow3 = if (page == KeyboardPage.SYMBOLS_1) {
                        listOf("*", "\"", "'", ":", ";", "!", "?")
                    } else {
                        listOf("%", "©", "®", "™", "✓", "[", "]")
                    }

                    KeyRow {
                        symRow1.forEach { sym ->
                            CharacterKey(sym, keyColor, keyTextColor) {
                                triggerHaptic(); onChar(sym)
                            }
                        }
                    }
                    KeyRow {
                        symRow2.forEach { sym ->
                            CharacterKey(sym, keyColor, keyTextColor) {
                                triggerHaptic(); onChar(sym)
                            }
                        }
                    }
                    KeyRow {
                        ActionKey(
                            weight = 1.5f,
                            bgColor = actionKeyColor,
                            onTap = {
                                triggerHaptic()
                                page = if (page == KeyboardPage.SYMBOLS_1) KeyboardPage.SYMBOLS_2 else KeyboardPage.SYMBOLS_1
                            }
                        ) {
                            Text(
                                text = if (page == KeyboardPage.SYMBOLS_1) "=\\<" else "?123",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = keyTextColor
                            )
                        }
                        symRow3.forEach { sym ->
                            CharacterKey(sym, keyColor, keyTextColor) {
                                triggerHaptic(); onChar(sym)
                            }
                        }
                        ActionKey(
                            weight = 1.5f,
                            bgColor = actionKeyColor,
                            onTap = { triggerHaptic(); onBackspace() }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Backspace,
                                contentDescription = "Backspace",
                                tint = keyTextColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // BOTTOM GBOARD ROW (?123, comma, Globe, Spacebar, period, circular Enter/Send)
            KeyRow {
                ActionKey(
                    weight = 1.45f,
                    bgColor = actionKeyColor,
                    onTap = {
                        triggerHaptic()
                        page = if (page == KeyboardPage.LETTERS) KeyboardPage.SYMBOLS_1 else KeyboardPage.LETTERS
                    },
                    testTag = "keyboard_symbols_key"
                ) {
                    Text(
                        text = if (page == KeyboardPage.LETTERS) "?123" else "ABC",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = keyTextColor
                    )
                }

                CharacterKey(",", actionKeyColor, keyTextColor, weight = 0.9f) {
                    triggerHaptic(); onChar(",")
                }

                ActionKey(
                    weight = 1.0f,
                    bgColor = actionKeyColor,
                    onTap = {
                        triggerHaptic()
                        onSwitchIme()
                    },
                    testTag = "keyboard_globe_key"
                ) {
                    Icon(
                        imageVector = Icons.Filled.Language,
                        contentDescription = "Switch keyboard",
                        tint = keyTextColor,
                        modifier = Modifier.size(19.dp)
                    )
                }

                // Gboard-style wide Spacebar with subtle language & encryption mode caption
                Box(
                    modifier = Modifier
                        .weight(4.1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(keyColor)
                        .clickable {
                            triggerHaptic()
                            onChar(" ")
                        }
                        .testTag("keyboard_space_key"),
                    contentAlignment = Alignment.Center
                ) {
                    val spaceCaption = when (translationDirection) {
                        TranslationDirection.OFF -> "CipherKey • E2E"
                        TranslationDirection.EN_TO_ES -> "CipherKey • EN→ES"
                        TranslationDirection.ES_TO_EN -> "CipherKey • ES→EN"
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SpaceBar,
                            contentDescription = "Space",
                            tint = mutedColor.copy(alpha = 0.5f),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = spaceCaption,
                            fontSize = 11.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = mutedColor
                        )
                    }
                }

                CharacterKey(".", actionKeyColor, keyTextColor, weight = 0.9f) {
                    triggerHaptic(); onChar(".")
                }

                // Iconic Gboard Material You rounded pill Enter / Send button
                ActionKey(
                    weight = 1.55f,
                    bgColor = EmeraldCipher,
                    onTap = {
                        triggerHaptic()
                        onEnter()
                    },
                    shape = RoundedCornerShape(23.dp),
                    testTag = "keyboard_enter_key"
                ) {
                    Icon(
                        imageVector = if (useSendIconForEnter) {
                            Icons.AutoMirrored.Filled.Send
                        } else {
                            Icons.AutoMirrored.Filled.KeyboardReturn
                        },
                        contentDescription = "Send or Enter",
                        tint = EmeraldOnPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
private fun RowScope.CharacterKey(
    label: String,
    keyColor: Color,
    textColor: Color,
    weight: Float = 1f,
    heightDp: Int = 46,
    onTap: () -> Unit
) {
    Box(
        modifier = Modifier
            .weight(weight)
            .height(heightDp.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(keyColor)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 20.sp,
            fontWeight = FontWeight.Normal,
            color = textColor
        )
    }
}

@Composable
private fun RowScope.ActionKey(
    weight: Float,
    bgColor: Color,
    onTap: () -> Unit,
    shape: RoundedCornerShape = RoundedCornerShape(7.dp),
    testTag: String? = null,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .weight(weight)
            .height(46.dp)
            .clip(shape)
            .background(bgColor)
            .clickable(onClick = onTap)
            .let { if (testTag != null) it.testTag(testTag) else it },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
