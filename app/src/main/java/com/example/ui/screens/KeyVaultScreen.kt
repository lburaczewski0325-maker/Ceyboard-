package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.crypto.CipherMode
import com.example.data.model.DecryptedVaultKey
import com.example.ui.CipherVaultViewModel
import com.example.ui.UiState
import com.example.ui.theme.AmberHyper
import com.example.ui.theme.EmeraldCipher
import com.example.ui.theme.EmeraldOnPrimary

@Composable
fun KeyVaultScreen(
    viewModel: CipherVaultViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val keysState by viewModel.vaultKeysState.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val banner by viewModel.keyboardBanner.collectAsStateWithLifecycle()

    var showSwapDialog by remember { mutableStateOf(false) }
    var showRegenerateConfirm by remember { mutableStateOf(false) }
    var feedbackToast by remember { mutableStateOf<String?>(null) }

    val keys = when (val state = keysState) {
        is UiState.Success -> state.data
        else -> emptyList()
    }
    val ownKey = remember(keys) { keys.firstOrNull { it.ownIdentity } }
    val peerKeys = remember(keys) { keys.filter { !it.ownIdentity } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                // Hero Banner with at-rest encryption status + sync button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(18.dp))
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_vault_hero),
                        contentDescription = "Local Encrypted Key Vault",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        Color(0xEE0D1117),
                                        Color(0x990D1117)
                                    )
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.EnhancedEncryption,
                                    contentDescription = null,
                                    tint = EmeraldCipher,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "AES-256-GCM AT REST",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EmeraldCipher,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            ConnectivityBadge(isOnline = isOnline, syncState = syncState)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Column {
                                Text(
                                    text = "Local Key Vault",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = Color.White
                                )
                                Text(
                                    text = "Swap keys once — zero contact book required",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFFB4BDC8)
                                )
                            }
                            IconButton(
                                onClick = { viewModel.syncVaultNow() },
                                modifier = Modifier.testTag("vault_sync_now_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Sync,
                                    contentDescription = "Sync vault now",
                                    tint = EmeraldCipher
                                )
                            }
                        }
                    }
                }
            }

            if (feedbackToast != null || banner != null) {
                item {
                    val msg = feedbackToast ?: banner?.message.orEmpty()
                    val isErr = banner?.isError == true && feedbackToast == null
                    Surface(
                        color = if (isErr) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isErr) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            // Own Public Key Card
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, EmeraldCipher.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Your Public Encryption Key",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Share this alphanumeric token once with any CipherKey user",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (ownKey != null) {
                                Surface(
                                    color = EmeraldCipher.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = ownKey.fingerprint,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = EmeraldCipher,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        if (keysState is UiState.Loading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        } else if (ownKey != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = ownKey.publicKeyToken,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("CipherKey Public Key", ownKey.publicKeyToken))
                                        feedbackToast = "Public key (${ownKey.fingerprint}) copied!"
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = EmeraldCipher,
                                        contentColor = EmeraldOnPrimary
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("vault_copy_own_key_button")
                                ) {
                                    Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Copy Key")
                                }
                                OutlinedButton(
                                    onClick = {
                                        val share = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_SUBJECT, "My CipherKey Public Encryption Key")
                                            putExtra(Intent.EXTRA_TEXT, ownKey.publicKeyToken)
                                        }
                                        context.startActivity(Intent.createChooser(share, "Share Public Key"))
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("vault_share_own_key_button")
                                ) {
                                    Icon(Icons.Filled.Share, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Share")
                                }
                                IconButton(
                                    onClick = { showRegenerateConfirm = true },
                                    modifier = Modifier.testTag("vault_regenerate_key_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Refresh,
                                        contentDescription = "Regenerate keypair"
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Key Bank Header + Swap Key Action
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Key Bank (${peerKeys.size})",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Saved peer keys unlock future messages automatically",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = { showSwapDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmeraldCipher,
                            contentColor = EmeraldOnPrimary
                        ),
                        modifier = Modifier.testTag("vault_add_key_button")
                    ) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Swap Key")
                    }
                }
            }

            if (peerKeys.isEmpty()) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Key,
                                contentDescription = null,
                                tint = AmberHyper,
                                modifier = Modifier.size(32.dp)
                            )
                            Text(
                                text = "Your Key Bank is Ready",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Paste an alphanumeric CK1 key from someone using CipherKey. Once saved in both vaults, you never have to swap keys again.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (ownKey != null) {
                                TextButton(
                                    onClick = {
                                        viewModel.importPeerKey(
                                            ownKey.publicKeyToken,
                                            "Self-Loopback Test",
                                            CipherMode.HYPER
                                        )
                                        feedbackToast = "Saved your own public key as a loopback test entry."
                                    },
                                    modifier = Modifier.testTag("vault_add_loopback_key_button")
                                ) {
                                    Text("Add My Own Key for Self-Test")
                                }
                            }
                        }
                    }
                }
            } else {
                items(peerKeys, key = { it.id }) { peer ->
                    VaultPeerKeyCard(
                        keyItem = peer,
                        isActive = peer.id == prefs.activeRecipientKeyId ||
                            (prefs.activeRecipientKeyId.isBlank() && peer == peerKeys.firstOrNull()),
                        onSelectActive = {
                            viewModel.selectActiveRecipientKey(peer.id)
                            feedbackToast = "Active encryption key set to ${peer.keyTag}"
                        },
                        onCopyToken = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText(peer.keyTag, peer.publicKeyToken))
                            feedbackToast = "Copied ${peer.keyTag} token"
                        },
                        onDelete = {
                            viewModel.deletePeerKey(peer.id)
                        }
                    )
                }
            }
        }
    }

    if (showSwapDialog) {
        SwapKeyDialog(
            onDismiss = { showSwapDialog = false },
            onConfirm = { token, tag, mode ->
                viewModel.importPeerKey(token, tag, mode)
                showSwapDialog = false
            }
        )
    }

    if (showRegenerateConfirm) {
        AlertDialog(
            onDismissRequest = { showRegenerateConfirm = false },
            title = { Text("Regenerate Your Keypair?") },
            text = {
                Text("Generating a new ECDH identity keypair means peers must save your new public key token to decrypt future messages.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.regenerateOwnIdentity()
                        showRegenerateConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Regenerate")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRegenerateConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun VaultPeerKeyCard(
    keyItem: DecryptedVaultKey,
    isActive: Boolean,
    onSelectActive: () -> Unit,
    onCopyToken: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isActive) 1.5.dp else 1.dp,
                color = if (isActive) EmeraldCipher else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(14.dp)
            )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isActive) Icons.Filled.CheckCircle else Icons.Filled.Key,
                        contentDescription = null,
                        tint = if (isActive) EmeraldCipher else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = keyItem.keyTag,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "FP: ${keyItem.fingerprint}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (keyItem.cipherMode == CipherMode.HYPER) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Filled.Bolt,
                                        contentDescription = null,
                                        tint = AmberHyper,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = "HYPER",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AmberHyper
                                    )
                                }
                            }
                            if (keyItem.pendingSync) {
                                Text(
                                    text = "• Queued Sync",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AmberHyper
                                )
                            }
                        }
                    }
                }

                Row {
                    IconButton(onClick = onCopyToken) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "Copy public key",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Delete key",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = keyItem.publicKeyToken,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onSelectActive,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(
                        text = if (isActive) "Active Target" else "Use Key",
                        fontSize = 11.sp,
                        color = if (isActive) EmeraldCipher else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun SwapKeyDialog(
    onDismiss: () -> Unit,
    onConfirm: (token: String, tag: String, mode: CipherMode) -> Unit
) {
    var token by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(CipherMode.HYPER) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Swap Alphanumeric Key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Paste a peer's CK1... public key. No contact card or phone number needed.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Public Key Token (CK1...)") },
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("swap_dialog_token_input")
                )
                OutlinedTextField(
                    value = tag,
                    onValueChange = { tag = it },
                    label = { Text("Key Tag (optional, e.g. Alex)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("swap_dialog_tag_input")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == CipherMode.HYPER,
                        onClick = { mode = CipherMode.HYPER },
                        label = { Text("Hyper AES-256") }
                    )
                    FilterChip(
                        selected = mode == CipherMode.STANDARD,
                        onClick = { mode = CipherMode.STANDARD },
                        label = { Text("Standard E2E") }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(token, tag, mode) },
                enabled = token.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldCipher,
                    contentColor = EmeraldOnPrimary
                ),
                modifier = Modifier.testTag("swap_dialog_save_button")
            ) {
                Text("Save to Vault")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
