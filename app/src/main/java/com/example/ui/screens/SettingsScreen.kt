package com.example.ui.screens

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.example.R
import com.example.features.UpdateManager
import com.example.ui.CipherVaultViewModel
import com.example.ui.signOutUser
import com.example.ui.theme.EmeraldCipher
import com.example.ui.theme.EmeraldOnPrimary
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import java.util.Date

@Composable
fun SettingsScreen(
    viewModel: CipherVaultViewModel,
    updateManager: UpdateManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val currentUser = Firebase.auth.currentUser

    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val updateStatus by viewModel.updateStatusMessage.collectAsStateWithLifecycle()
    val offlinePackStatus by viewModel.offlinePackMessage.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

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
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // 1. KEYBOARD & CRYPTOGRAPHY PREFERENCES
            item {
                SettingsSectionCard(title = "Keyboard & Cryptography") {
                    SettingsToggleRow(
                        icon = Icons.Filled.Bolt,
                        title = "Hyper Encryption by Default",
                        subtitle = "Dual-ECDH + double-layer AES-256-GCM + length padding",
                        checked = prefs.hyperEncryptionDefault,
                        onCheckedChange = { viewModel.setHyperDefault(it) },
                        testTag = "settings_hyper_toggle"
                    )
                    SettingsToggleRow(
                        icon = Icons.Filled.Translate,
                        title = "Show Top Number Row",
                        subtitle = "Keep 1–0 row visible above Gboard QWERTY keys",
                        checked = prefs.numberRowVisible,
                        onCheckedChange = { viewModel.setNumberRowVisible(it) },
                        testTag = "settings_number_row_toggle"
                    )
                    SettingsToggleRow(
                        icon = Icons.Filled.Vibration,
                        title = "Keypress Haptic Feedback",
                        subtitle = "Tactile pulse on key taps and Lock/Unlock",
                        checked = prefs.hapticFeedbackEnabled,
                        onCheckedChange = { viewModel.setHapticEnabled(it) },
                        testTag = "settings_haptic_toggle"
                    )
                }
            }

            // 2. OFFLINE EN <-> ES TRANSLATION PACK
            item {
                SettingsSectionCard(title = "Offline Translation (EN ↔ ES)") {
                    Text(
                        text = if (prefs.offlineEsModelReady) {
                            "On-device English & Spanish translation pack is installed for offline messaging."
                        } else {
                            "Uses Gemini AI when online. Download the on-device neural pack for 100% offline EN ↔ ES translation."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (offlinePackStatus != null) {
                        Text(
                            text = offlinePackStatus ?: "",
                            style = MaterialTheme.typography.labelMedium,
                            color = EmeraldCipher
                        )
                    }
                    OutlinedButton(
                        onClick = { viewModel.downloadOfflineTranslationPack() },
                        enabled = !isBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_download_translation_pack")
                    ) {
                        Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (prefs.offlineEsModelReady) "Verify Offline EN ↔ ES Pack"
                            else "Download Offline EN ↔ ES Pack"
                        )
                    }
                }
            }

            // 3. APP UPDATES & APK SHARING (PLAY CREATOR PROGRAM COMPLIANT)
            item {
                val appVersion = stringResource(R.string.app_version_name)
                val lastCheckText = remember(prefs.lastUpdateCheckMillis) {
                    if (prefs.lastUpdateCheckMillis == 0L) "Never checked"
                    else DateFormat.format("MMM d, yyyy h:mm a", Date(prefs.lastUpdateCheckMillis)).toString()
                }
                SettingsSectionCard(title = "App Updates & APK Sharing") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "CipherKey v$appVersion",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Last update check: $lastCheckText",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.SystemUpdate,
                            contentDescription = null,
                            tint = EmeraldCipher
                        )
                    }

                    SettingsToggleRow(
                        icon = Icons.Filled.SystemUpdate,
                        title = "Background Update Checks",
                        subtitle = "Periodically checks release status automatically via WorkManager",
                        checked = prefs.autoCheckUpdates,
                        onCheckedChange = { viewModel.setAutoCheckUpdates(it) },
                        testTag = "settings_background_updates_toggle"
                    )

                    if (updateStatus != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = updateStatus ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.checkUpdatesNow() },
                            enabled = !isBusy,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("settings_check_updates_button")
                        ) {
                            Text("Check Updates")
                        }
                        Button(
                            onClick = {
                                scope.launch {
                                    viewModel.showBanner("Preparing APK download...")
                                    updateManager.downloadAndInstallApk(context) { status ->
                                        viewModel.showBanner(status)
                                    }.onFailure { err ->
                                        viewModel.showBanner("Install failed: ${err.message}", isError = true)
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldCipher,
                                contentColor = EmeraldOnPrimary
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("settings_download_apk_button")
                        ) {
                            Icon(Icons.Filled.Download, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Download & Install", fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedButton(
                        onClick = { updateManager.shareApkDownloadLink(context) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_share_apk_link_button")
                    ) {
                        Icon(Icons.Filled.Share, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Share APK Download Link Across Devices")
                    }
                }
            }

            // 4. GOOGLE ACCOUNT & CLOUD VAULT SYNC (OPTIONAL)
            item {
                SettingsSectionCard(title = "Account & Cloud Sync (Optional)") {
                    if (currentUser == null) {
                        Text(
                            text = "CipherKey runs fully offline and standalone. Optionally sign in with Google to sync your Key Bank across your devices.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                com.example.ui.onGoogleSignInClicked(
                                    context = context,
                                    credentialManager = credentialManager,
                                    onAuthSuccess = {
                                        viewModel.showBanner("Signed in successfully. Syncing Key Bank...")
                                    },
                                    onAuthError = { err ->
                                        viewModel.showBanner(err, isError = true)
                                    },
                                    scope = scope
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldCipher,
                                contentColor = EmeraldOnPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_optional_signin_button")
                        ) {
                            Text("Sign in with Google (Optional)", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Text(
                            text = currentUser.email ?: currentUser.uid,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Your public Key Bank syncs privately under your Google account while private keys remain encrypted at rest on-device.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = {
                                signOutUser(credentialManager, scope) {
                                    viewModel.showBanner("Signed out. Operating in standalone local mode.")
                                }
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_sign_out_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Logout, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Sign Out")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            content()
        }
    }
}

@Composable
private fun SettingsToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = EmeraldCipher,
            modifier = Modifier.size(20.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}
