package com.example.data.model

import androidx.annotation.Keep
import com.example.crypto.CipherMode
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class TranslationDirection(val label: String) {
    OFF("Off"),
    EN_TO_ES("EN → ES"),
    ES_TO_EN("ES → EN");

    companion object {
        fun safeFrom(raw: String?): TranslationDirection =
            entries.firstOrNull { it.name == raw } ?: OFF
    }
}

@Keep
@IgnoreExtraProperties
data class VaultKeyRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val keyTag: String = "",
    val fingerprint: String = "",
    val publicKeyToken: String = "",
    val cipherMode: String = CipherMode.HYPER.name,
    val encryptedSharedWrap: String? = null,
    val ownIdentity: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun parsedCipherMode(): CipherMode =
        CipherMode.entries.firstOrNull { it.name == cipherMode } ?: CipherMode.HYPER

    fun toCreateMap(): Map<String, Any> {
        val raw = mapOf(
            "userId" to userId,
            "keyTag" to keyTag.take(40).ifBlank { "KEY-$fingerprint" },
            "fingerprint" to fingerprint,
            "publicKeyToken" to publicKeyToken,
            "cipherMode" to parsedCipherMode().name,
            "encryptedSharedWrap" to encryptedSharedWrap,
            "ownIdentity" to ownIdentity,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        @Suppress("UNCHECKED_CAST")
        return raw.filterValues { it != null } as Map<String, Any>
    }
}

@Keep
@IgnoreExtraProperties
data class UserPreferencesRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val hyperEncryptionDefault: Boolean = true,
    val translationDirection: String = TranslationDirection.OFF.name,
    val numberRowVisible: Boolean = true,
    val hapticFeedbackEnabled: Boolean = true,
    val autoCheckUpdates: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "userId" to userId,
        "hyperEncryptionDefault" to hyperEncryptionDefault,
        "translationDirection" to TranslationDirection.safeFrom(translationDirection).name,
        "numberRowVisible" to numberRowVisible,
        "hapticFeedbackEnabled" to hapticFeedbackEnabled,
        "autoCheckUpdates" to autoCheckUpdates,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

data class DecryptedVaultKey(
    val id: String,
    val keyTag: String,
    val fingerprint: String,
    val publicKeyToken: String,
    val publicKeyBytes: ByteArray,
    val privateKeyBytes: ByteArray?,
    val cipherMode: CipherMode,
    val ownIdentity: Boolean,
    val pendingSync: Boolean,
    val updatedAtMillis: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecryptedVaultKey) return false
        return id == other.id &&
            keyTag == other.keyTag &&
            fingerprint == other.fingerprint &&
            publicKeyToken == other.publicKeyToken &&
            cipherMode == other.cipherMode &&
            ownIdentity == other.ownIdentity &&
            pendingSync == other.pendingSync &&
            updatedAtMillis == other.updatedAtMillis &&
            publicKeyBytes.contentEquals(other.publicKeyBytes) &&
            ((privateKeyBytes == null && other.privateKeyBytes == null) ||
                (privateKeyBytes != null && other.privateKeyBytes != null && privateKeyBytes.contentEquals(other.privateKeyBytes)))
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + keyTag.hashCode()
        result = 31 * result + fingerprint.hashCode()
        result = 31 * result + cipherMode.hashCode()
        result = 31 * result + ownIdentity.hashCode()
        result = 31 * result + pendingSync.hashCode()
        return result
    }
}
