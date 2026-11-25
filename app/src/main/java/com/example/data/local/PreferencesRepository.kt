package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.TranslationDirection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.cipherPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "cipherkey_preferences"
)

data class KeyboardPreferences(
    val hyperEncryptionDefault: Boolean = true,
    val translationDirection: TranslationDirection = TranslationDirection.OFF,
    val numberRowVisible: Boolean = true,
    val hapticFeedbackEnabled: Boolean = true,
    val autoCheckUpdates: Boolean = true,
    val activeRecipientKeyId: String = "",
    val lastUpdateCheckMillis: Long = 0L,
    val latestAvailableVersion: String = "1.0.0",
    val offlineEsModelReady: Boolean = false
)

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val HYPER_DEFAULT = booleanPreferencesKey("hyper_encryption_default")
        val TRANSLATION_DIR = stringPreferencesKey("translation_direction")
        val NUMBER_ROW = booleanPreferencesKey("number_row_visible")
        val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback_enabled")
        val AUTO_UPDATE_CHECK = booleanPreferencesKey("auto_check_updates")
        val ACTIVE_RECIPIENT_ID = stringPreferencesKey("active_recipient_key_id")
        val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check_millis")
        val LATEST_VERSION = stringPreferencesKey("latest_available_version")
        val OFFLINE_ES_MODEL = booleanPreferencesKey("offline_es_model_ready")
    }

    val preferences: Flow<KeyboardPreferences> = context.cipherPrefsDataStore.data.map { prefs ->
        KeyboardPreferences(
            hyperEncryptionDefault = prefs[Keys.HYPER_DEFAULT] ?: true,
            translationDirection = TranslationDirection.safeFrom(prefs[Keys.TRANSLATION_DIR]),
            numberRowVisible = prefs[Keys.NUMBER_ROW] ?: true,
            hapticFeedbackEnabled = prefs[Keys.HAPTIC_FEEDBACK] ?: true,
            autoCheckUpdates = prefs[Keys.AUTO_UPDATE_CHECK] ?: true,
            activeRecipientKeyId = prefs[Keys.ACTIVE_RECIPIENT_ID] ?: "",
            lastUpdateCheckMillis = prefs[Keys.LAST_UPDATE_CHECK] ?: 0L,
            latestAvailableVersion = prefs[Keys.LATEST_VERSION] ?: "1.0.0",
            offlineEsModelReady = prefs[Keys.OFFLINE_ES_MODEL] ?: false
        )
    }

    suspend fun setHyperEncryptionDefault(enabled: Boolean) {
        context.cipherPrefsDataStore.edit { it[Keys.HYPER_DEFAULT] = enabled }
    }

    suspend fun setTranslationDirection(direction: TranslationDirection) {
        context.cipherPrefsDataStore.edit { it[Keys.TRANSLATION_DIR] = direction.name }
    }

    suspend fun setNumberRowVisible(visible: Boolean) {
        context.cipherPrefsDataStore.edit { it[Keys.NUMBER_ROW] = visible }
    }

    suspend fun setHapticFeedbackEnabled(enabled: Boolean) {
        context.cipherPrefsDataStore.edit { it[Keys.HAPTIC_FEEDBACK] = enabled }
    }

    suspend fun setAutoCheckUpdates(enabled: Boolean) {
        context.cipherPrefsDataStore.edit { it[Keys.AUTO_UPDATE_CHECK] = enabled }
    }

    suspend fun setActiveRecipientKeyId(keyId: String) {
        context.cipherPrefsDataStore.edit { it[Keys.ACTIVE_RECIPIENT_ID] = keyId }
    }

    suspend fun recordUpdateCheck(timestampMillis: Long, latestVersion: String) {
        context.cipherPrefsDataStore.edit {
            it[Keys.LAST_UPDATE_CHECK] = timestampMillis
            it[Keys.LATEST_VERSION] = latestVersion
        }
    }

    suspend fun setOfflineEsModelReady(ready: Boolean) {
        context.cipherPrefsDataStore.edit { it[Keys.OFFLINE_ES_MODEL] = ready }
    }
}
