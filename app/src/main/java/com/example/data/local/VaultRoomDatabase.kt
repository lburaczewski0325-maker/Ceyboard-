package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "vault_keys")
data class EncryptedVaultKeyEntity(
    @PrimaryKey val id: String,
    val ownerUid: String,
    val fingerprint: String,
    val encryptedTag: String,
    val encryptedPublicKey: String,
    val encryptedPrivateKey: String?,
    val cipherMode: String,
    val ownIdentity: Boolean,
    val pendingSync: Boolean,
    val pendingDelete: Boolean,
    val updatedAtMillis: Long
)

@Database(
    entities = [EncryptedVaultKeyEntity::class],
    version = 1,
    exportSchema = false
)
abstract class CipherVaultDatabase : RoomDatabase() {
    abstract fun vaultDao(): VaultDao

    companion object {
        @Volatile
        private var INSTANCE: CipherVaultDatabase? = null

        fun getInstance(context: Context): CipherVaultDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    CipherVaultDatabase::class.java,
                    "cipherkey_encrypted_vault.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

@Dao
interface VaultDao {
    @Query("SELECT * FROM vault_keys WHERE ownerUid = :uid AND pendingDelete = 0 ORDER BY ownIdentity DESC, updatedAtMillis DESC")
    fun observeKeysForUser(uid: String): Flow<List<EncryptedVaultKeyEntity>>

    @Query("SELECT * FROM vault_keys WHERE ownerUid = :uid AND pendingDelete = 0 ORDER BY ownIdentity DESC, updatedAtMillis DESC")
    suspend fun getKeysForUserOnce(uid: String): List<EncryptedVaultKeyEntity>

    @Query("SELECT * FROM vault_keys WHERE ownerUid = :uid AND pendingSync = 1")
    suspend fun getPendingSyncKeys(uid: String): List<EncryptedVaultKeyEntity>

    @Query("SELECT * FROM vault_keys WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): EncryptedVaultKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: EncryptedVaultKeyEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<EncryptedVaultKeyEntity>)

    @Query("DELETE FROM vault_keys WHERE id = :id")
    suspend fun hardDeleteById(id: String)

    @Query("DELETE FROM vault_keys WHERE ownerUid = :uid")
    suspend fun clearForUser(uid: String)
}
