package com.example.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Base64
import android.util.Log
import com.example.R
import com.example.crypto.CipherMode
import com.example.crypto.DecryptedCipherPayload
import com.example.crypto.KeystoreCryptoEngine
import com.example.data.local.CipherVaultDatabase
import com.example.data.local.EncryptedVaultKeyEntity
import com.example.data.local.PreferencesRepository
import com.example.data.model.DecryptedVaultKey
import com.example.data.model.TranslationDirection
import com.example.data.model.UserPreferencesRecord
import com.example.data.model.VaultKeyRecord
import com.example.firebase.OperationType
import com.example.firebase.handleFirestoreError
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.firestoreSettings
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

sealed interface SyncState {
    data object OfflineLocalOnly : SyncState
    data object Syncing : SyncState
    data class Synced(val pendingCount: Int = 0) : SyncState
    data class Error(val message: String) : SyncState
}

class VaultRepository(
    context: Context,
    private val db: FirebaseFirestore,
    private val localDb: CipherVaultDatabase,
    private val crypto: KeystoreCryptoEngine,
    private val prefsRepo: PreferencesRepository,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        context = context.applicationContext,
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ).apply {
            runCatching {
                firestoreSettings = firestoreSettings {
                    setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                }
            }
        },
        localDb = CipherVaultDatabase.getInstance(context.applicationContext),
        crypto = KeystoreCryptoEngine(),
        prefsRepo = PreferencesRepository(context.applicationContext)
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao = localDb.vaultDao()

    private val _isOnline = MutableStateFlow(checkCurrentConnectivity())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _syncState = MutableStateFlow<SyncState>(
        if (_isOnline.value) SyncState.Synced(0) else SyncState.OfflineLocalOnly
    )
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    init {
        registerNetworkMonitor()
    }

    private fun checkCurrentConnectivity(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun registerNetworkMonitor() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching {
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _isOnline.value = true
                    auth.currentUser?.uid?.let { uid ->
                        scope.launch { syncPendingChanges(uid) }
                    }
                }

                override fun onLost(network: Network) {
                    val stillOnline = checkCurrentConnectivity()
                    _isOnline.value = stillOnline
                    if (!stillOnline) {
                        _syncState.value = SyncState.OfflineLocalOnly
                    }
                }
            })
        }
    }

    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing cloud vault.")

    /**
     * Observes the locally encrypted Room vault for [userId]. Works 100% offline,
     * including from the background InputMethodService.
     */
    fun observeLocalVaultKeys(userId: String): Flow<List<DecryptedVaultKey>> =
        dao.observeKeysForUser(userId).map { entities ->
            entities.mapNotNull { entity -> decryptEntity(entity) }
        }

    /**
     * Observes Firestore cloud vault_keys for [userId] and reconciles remote updates
     * into the encrypted local Room vault.
     */
    fun observeCloudVaultStream(userId: String): Flow<List<VaultKeyRecord>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Signed-in user mismatch." }
        val path = "users/$uid/vault_keys"
        emitAll(
            db.collection("users").document(uid).collection("vault_keys")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        VaultKeyRecord::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun reconcileRemoteRecords(userId: String, remoteRecords: List<VaultKeyRecord>) {
        if (remoteRecords.isEmpty()) return
        for (record in remoteRecords) {
            if (record.id.isBlank()) continue
            val existingLocal = dao.getById(record.id)
            if (existingLocal != null && existingLocal.pendingSync) {
                continue
            }
            val parsedPub = runCatching { crypto.parsePublicKeyToken(record.publicKeyToken) }.getOrNull()
                ?: continue
            val existingPriv = existingLocal?.encryptedPrivateKey
            val entity = EncryptedVaultKeyEntity(
                id = record.id,
                ownerUid = userId,
                fingerprint = record.fingerprint.ifBlank { parsedPub.second },
                encryptedTag = crypto.encryptStringAtRest(record.keyTag.ifBlank { "KEY-${parsedPub.second}" }),
                encryptedPublicKey = crypto.encryptAtRest(parsedPub.first),
                encryptedPrivateKey = existingPriv,
                cipherMode = record.parsedCipherMode().name,
                ownIdentity = record.ownIdentity,
                pendingSync = false,
                pendingDelete = false,
                updatedAtMillis = record.updatedAt?.toDate()?.time ?: System.currentTimeMillis()
            )
            dao.upsert(entity)
        }
        val pendingCount = dao.getPendingSyncKeys(userId).size
        _syncState.value = if (_isOnline.value) SyncState.Synced(pendingCount) else SyncState.OfflineLocalOnly
    }

    /**
     * Ensures the user has a local hardware-backed identity keypair (encrypted at rest).
     * Creates one if missing and queues it for Firestore sync.
     */
    suspend fun ensureOwnIdentity(userId: String): Result<DecryptedVaultKey> {
        return try {
            val currentKeys = dao.getKeysForUserOnce(userId).mapNotNull { decryptEntity(it) }
            val existingOwn = currentKeys.firstOrNull { it.ownIdentity && it.privateKeyBytes != null }
            if (existingOwn != null) {
                return Result.success(existingOwn)
            }

            val generated = crypto.generateIdentity()
            val keyId = "identity_${generated.fingerprint.lowercase()}"
            val entity = EncryptedVaultKeyEntity(
                id = keyId,
                ownerUid = userId,
                fingerprint = generated.fingerprint,
                encryptedTag = crypto.encryptStringAtRest("My Identity Key"),
                encryptedPublicKey = crypto.encryptAtRest(generated.publicKeyBytes),
                encryptedPrivateKey = crypto.encryptAtRest(generated.privateKeyBytes),
                cipherMode = CipherMode.HYPER.name,
                ownIdentity = true,
                pendingSync = true,
                pendingDelete = false,
                updatedAtMillis = System.currentTimeMillis()
            )
            dao.upsert(entity)
            if (_isOnline.value && auth.currentUser?.uid == userId) {
                syncPendingChanges(userId)
            } else {
                _syncState.value = SyncState.OfflineLocalOnly
            }
            Result.success(
                DecryptedVaultKey(
                    id = keyId,
                    keyTag = "My Identity Key",
                    fingerprint = generated.fingerprint,
                    publicKeyToken = generated.publicKeyToken,
                    publicKeyBytes = generated.publicKeyBytes,
                    privateKeyBytes = generated.privateKeyBytes,
                    cipherMode = CipherMode.HYPER,
                    ownIdentity = true,
                    pendingSync = true,
                    updatedAtMillis = entity.updatedAtMillis
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure own identity", e)
            Result.failure(e)
        }
    }

    suspend fun regenerateOwnIdentity(userId: String): Result<DecryptedVaultKey> {
        return try {
            val existingOwn = dao.getKeysForUserOnce(userId).filter { it.ownIdentity }
            for (old in existingOwn) {
                dao.upsert(old.copy(pendingDelete = true, pendingSync = true))
            }
            val generated = crypto.generateIdentity()
            val keyId = "identity_${generated.fingerprint.lowercase()}_${System.currentTimeMillis() % 10000}"
            val entity = EncryptedVaultKeyEntity(
                id = keyId,
                ownerUid = userId,
                fingerprint = generated.fingerprint,
                encryptedTag = crypto.encryptStringAtRest("My Identity Key"),
                encryptedPublicKey = crypto.encryptAtRest(generated.publicKeyBytes),
                encryptedPrivateKey = crypto.encryptAtRest(generated.privateKeyBytes),
                cipherMode = CipherMode.HYPER.name,
                ownIdentity = true,
                pendingSync = true,
                pendingDelete = false,
                updatedAtMillis = System.currentTimeMillis()
            )
            dao.upsert(entity)
            if (_isOnline.value && auth.currentUser?.uid == userId) {
                syncPendingChanges(userId)
            }
            Result.success(decryptEntity(entity)!!)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Adds or updates a swapped alphanumeric public key in the user's encrypted local vault
     * (no contact book or phone/email needed) and syncs when online.
     */
    suspend fun addOrUpdatePeerKey(
        userId: String,
        publicKeyToken: String,
        rawTag: String,
        cipherMode: CipherMode
    ): Result<DecryptedVaultKey> {
        return try {
            val (pubBytes, fingerprint) = crypto.parsePublicKeyToken(publicKeyToken)
            val safeTag = rawTag.trim().take(40).ifBlank { "KEY-$fingerprint" }
            val existing = dao.getKeysForUserOnce(userId)
                .firstOrNull { !it.ownIdentity && it.fingerprint == fingerprint }
            val keyId = existing?.id ?: "peer_${fingerprint.lowercase()}_${UUID.randomUUID().toString().take(6)}"
            val now = System.currentTimeMillis()
            val entity = EncryptedVaultKeyEntity(
                id = keyId,
                ownerUid = userId,
                fingerprint = fingerprint,
                encryptedTag = crypto.encryptStringAtRest(safeTag),
                encryptedPublicKey = crypto.encryptAtRest(pubBytes),
                encryptedPrivateKey = null,
                cipherMode = cipherMode.name,
                ownIdentity = false,
                pendingSync = true,
                pendingDelete = false,
                updatedAtMillis = now
            )
            dao.upsert(entity)
            prefsRepo.setActiveRecipientKeyId(keyId)
            if (_isOnline.value && auth.currentUser?.uid == userId) {
                syncPendingChanges(userId)
            } else {
                _syncState.value = SyncState.OfflineLocalOnly
            }
            Result.success(decryptEntity(entity)!!)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save peer key", e)
            Result.failure(e)
        }
    }

    suspend fun deleteVaultKey(userId: String, keyId: String): Result<Unit> {
        return try {
            val entity = dao.getById(keyId) ?: return Result.success(Unit)
            if (entity.ownIdentity) {
                return Result.failure(IllegalArgumentException("Use Regenerate to replace your own active identity key."))
            }
            dao.upsert(entity.copy(pendingDelete = true, pendingSync = true, updatedAtMillis = System.currentTimeMillis()))
            val activeId = prefsRepo.preferences.first().activeRecipientKeyId
            if (activeId == keyId) {
                prefsRepo.setActiveRecipientKeyId("")
            }
            if (_isOnline.value && auth.currentUser?.uid == userId) {
                syncPendingChanges(userId)
            } else {
                _syncState.value = SyncState.OfflineLocalOnly
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun syncPendingChanges(userId: String): Result<Unit> {
        val currentUid = auth.currentUser?.uid
        if (currentUid.isNullOrBlank() || currentUid != userId) {
            return Result.failure(IllegalStateException("Google Sign-In required to sync cloud vault."))
        }
        if (!_isOnline.value) {
            _syncState.value = SyncState.OfflineLocalOnly
            return Result.success(Unit)
        }
        _syncState.value = SyncState.Syncing
        return try {
            val pending = dao.getPendingSyncKeys(userId)
            for (entity in pending) {
                val docPath = "users/$userId/vault_keys/${entity.id}"
                val docRef = db.collection("users").document(userId)
                    .collection("vault_keys").document(entity.id)
                if (entity.pendingDelete) {
                    try {
                        docRef.delete().await()
                        dao.hardDeleteById(entity.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        handleFirestoreError(e, OperationType.DELETE, docPath)
                        throw e
                    }
                } else {
                    val decrypted = decryptEntity(entity) ?: continue
                    val existingDoc = try {
                        docRef.get().await()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        handleFirestoreError(e, OperationType.GET, docPath)
                        throw e
                    }
                    if (existingDoc.exists()) {
                        try {
                            docRef.update(
                                mapOf(
                                    "keyTag" to decrypted.keyTag.take(40),
                                    "fingerprint" to decrypted.fingerprint,
                                    "publicKeyToken" to decrypted.publicKeyToken,
                                    "cipherMode" to decrypted.cipherMode.name,
                                    "updatedAt" to FieldValue.serverTimestamp()
                                )
                            ).await()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            handleFirestoreError(e, OperationType.UPDATE, docPath)
                            throw e
                        }
                    } else {
                        val record = VaultKeyRecord(
                            userId = userId,
                            keyTag = decrypted.keyTag.take(40),
                            fingerprint = decrypted.fingerprint,
                            publicKeyToken = decrypted.publicKeyToken,
                            cipherMode = decrypted.cipherMode.name,
                            encryptedSharedWrap = Base64.encodeToString(
                                decrypted.publicKeyBytes,
                                Base64.NO_WRAP
                            ).take(512),
                            ownIdentity = decrypted.ownIdentity
                        )
                        try {
                            docRef.set(record.toCreateMap()).await()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            handleFirestoreError(e, OperationType.CREATE, docPath)
                            throw e
                        }
                    }
                    dao.upsert(entity.copy(pendingSync = false))
                }
            }
            syncPreferencesToCloud(userId)
            _syncState.value = SyncState.Synced(0)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cloud sync deferred: ${e.message}")
            _syncState.value = SyncState.Error(e.localizedMessage ?: "Sync failed")
            Result.failure(e)
        }
    }

    suspend fun syncPreferencesToCloud(userId: String): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        if (uid != userId || !_isOnline.value) return Result.success(Unit)
        val docPath = "users/$userId/preferences/settings"
        return try {
            val prefs = prefsRepo.preferences.first()
            val docRef = db.collection("users").document(userId)
                .collection("preferences").document("settings")
            val snap = docRef.get().await()
            if (snap.exists()) {
                docRef.update(
                    mapOf(
                        "hyperEncryptionDefault" to prefs.hyperEncryptionDefault,
                        "translationDirection" to prefs.translationDirection.name,
                        "numberRowVisible" to prefs.numberRowVisible,
                        "hapticFeedbackEnabled" to prefs.hapticFeedbackEnabled,
                        "autoCheckUpdates" to prefs.autoCheckUpdates,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                val model = UserPreferencesRecord(
                    userId = userId,
                    hyperEncryptionDefault = prefs.hyperEncryptionDefault,
                    translationDirection = prefs.translationDirection.name,
                    numberRowVisible = prefs.numberRowVisible,
                    hapticFeedbackEnabled = prefs.hapticFeedbackEnabled,
                    autoCheckUpdates = prefs.autoCheckUpdates
                )
                docRef.set(model.toCreateMap()).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    /**
     * Encrypts [plaintext] using the user's local identity key and the selected recipient's public key
     * (works 100% offline from encrypted local Room storage).
     */
    suspend fun encryptTextWithActiveKey(
        plaintext: String,
        recipientKeyId: String?,
        mode: CipherMode
    ): Result<String> {
        return try {
            val uid = auth.currentUser?.uid ?: "local_offline_user"
            val keys = getActiveUserKeys(uid)
            val ownKey = keys.firstOrNull { it.ownIdentity && it.privateKeyBytes != null }
                ?: return Result.failure(IllegalStateException("Generate your identity key in Key Vault first."))
            val peerKey = keys.firstOrNull { it.id == recipientKeyId }
                ?: keys.firstOrNull { !it.ownIdentity }
                ?: ownKey // Allow loopback self-encryption if no peer key added yet
            val encrypted = crypto.encryptMessage(
                plaintext = plaintext,
                ownPrivateKeyBytes = ownKey.privateKeyBytes!!,
                ownPublicKeyBytes = ownKey.publicKeyBytes,
                recipientPublicKeyBytes = peerKey.publicKeyBytes,
                mode = mode
            )
            Result.success(encrypted)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Decrypts [cipherText] automatically matching the sender/recipient fingerprint against
     * any key saved in the user's Key Bank (zero contact book or re-swapping needed!).
     */
    suspend fun decryptTextFromVault(cipherText: String): Result<Pair<DecryptedCipherPayload, String>> {
        return try {
            val uid = auth.currentUser?.uid ?: "local_offline_user"
            val keys = getActiveUserKeys(uid)
            val ownKey = keys.firstOrNull { it.ownIdentity && it.privateKeyBytes != null }
                ?: return Result.failure(IllegalStateException("No local identity key found in Vault."))
            val (senderFp, recipientFp) = crypto.extractHeaderFingerprints(cipherText)
                ?: return Result.failure(IllegalArgumentException("No CipherKey encrypted payload found."))

            val peerFp = if (senderFp == ownKey.fingerprint) recipientFp else senderFp
            val matchingPeer = keys.firstOrNull { it.fingerprint == peerFp }
                ?: return Result.failure(
                    IllegalStateException("Sender key ($peerFp) not found in your Key Bank. Swap keys once to decrypt.")
                )

            val decrypted = crypto.decryptMessage(
                cipherText = cipherText,
                ownPrivateKeyBytes = ownKey.privateKeyBytes!!,
                ownPublicKeyBytes = ownKey.publicKeyBytes,
                peerPublicKeyBytes = matchingPeer.publicKeyBytes
            )
            Result.success(decrypted to matchingPeer.keyTag)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getKeysSnapshotForIme(): List<DecryptedVaultKey> {
        val uid = auth.currentUser?.uid ?: return emptyList()
        return getActiveUserKeys(uid)
    }

    private suspend fun getActiveUserKeys(uid: String): List<DecryptedVaultKey> =
        dao.getKeysForUserOnce(uid).mapNotNull { decryptEntity(it) }

    private fun decryptEntity(entity: EncryptedVaultKeyEntity): DecryptedVaultKey? {
        return runCatching {
            val pubBytes = crypto.decryptAtRest(entity.encryptedPublicKey)
            val privBytes = entity.encryptedPrivateKey?.let { crypto.decryptAtRest(it) }
            val tag = crypto.decryptStringAtRest(entity.encryptedTag)
            val token = crypto.encodePublicKeyToken(pubBytes)
            val mode = CipherMode.entries.firstOrNull { it.name == entity.cipherMode } ?: CipherMode.HYPER
            DecryptedVaultKey(
                id = entity.id,
                keyTag = tag,
                fingerprint = entity.fingerprint,
                publicKeyToken = token,
                publicKeyBytes = pubBytes,
                privateKeyBytes = privBytes,
                cipherMode = mode,
                ownIdentity = entity.ownIdentity,
                pendingSync = entity.pendingSync,
                updatedAtMillis = entity.updatedAtMillis
            )
        }.getOrNull()
    }

    private companion object {
        const val TAG = "VaultRepository"
    }
}
