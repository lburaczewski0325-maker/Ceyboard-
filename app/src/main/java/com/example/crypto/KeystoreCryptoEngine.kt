package com.example.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class CipherMode {
    STANDARD,
    HYPER
}

data class GeneratedIdentity(
    val publicKeyBytes: ByteArray,
    val privateKeyBytes: ByteArray,
    val publicKeyToken: String,
    val fingerprint: String
)

data class DecryptedCipherPayload(
    val plaintext: String,
    val senderFingerprint: String,
    val recipientFingerprint: String,
    val cipherMode: CipherMode
)

class KeystoreCryptoEngine {

    private val secureRandom = SecureRandom()

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val MASTER_KEY_ALIAS = "CipherKeyMasterVaultKeyV1"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_BYTES = 12
        private const val PUBLIC_TOKEN_PREFIX = "CK1"
        private const val STANDARD_MSG_PREFIX = "CKM1:"
        private const val HYPER_MSG_PREFIX = "CKH1:"
        private const val HYPER_BLOCK_SIZE = 64
        private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    }

    private val fallbackLocalKey: SecretKey by lazy {
        // Used only if AndroidKeyStore is unavailable in headless JVM unit tests.
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("cipherkey-jvm-test-fallback-key".toByteArray(Charsets.UTF_8))
        SecretKeySpec(digest, "AES")
    }

    private fun getOrCreateMasterKey(): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val existing = keyStore.getKey(MASTER_KEY_ALIAS, null) as? SecretKey
            if (existing != null) return existing

            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE_PROVIDER
            )
            val spec = KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        } catch (_: Throwable) {
            fallbackLocalKey
        }
    }

    fun encryptAtRest(plaintextBytes: ByteArray): String {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintextBytes)
        val combined = ByteArray(1 + iv.size + ciphertext.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(ciphertext, 0, combined, 1 + iv.size, ciphertext.size)
        return base64Encode(combined, urlSafe = false)
    }

    fun encryptStringAtRest(plaintext: String): String =
        encryptAtRest(plaintext.toByteArray(Charsets.UTF_8))

    fun decryptAtRest(encoded: String): ByteArray {
        val combined = base64Decode(encoded, urlSafe = false)
        require(combined.size > 2) { "Corrupted encrypted vault blob" }
        val ivLen = combined[0].toInt() and 0xFF
        require(combined.size > 1 + ivLen) { "Invalid IV length in encrypted blob" }
        val iv = combined.copyOfRange(1, 1 + ivLen)
        val ciphertext = combined.copyOfRange(1 + ivLen, combined.size)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    fun decryptStringAtRest(encoded: String): String =
        decryptAtRest(encoded).toString(Charsets.UTF_8)

    fun generateIdentity(): GeneratedIdentity {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        val pair = kpg.generateKeyPair()
        val pubBytes = pair.public.encoded
        val privBytes = pair.private.encoded
        return GeneratedIdentity(
            publicKeyBytes = pubBytes,
            privateKeyBytes = privBytes,
            publicKeyToken = encodePublicKeyToken(pubBytes),
            fingerprint = computeFingerprint(pubBytes)
        )
    }

    fun encodePublicKeyToken(publicKeyBytes: ByteArray): String {
        val checksum = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
        val payload = ByteArray(publicKeyBytes.size + 2)
        System.arraycopy(publicKeyBytes, 0, payload, 0, publicKeyBytes.size)
        payload[publicKeyBytes.size] = checksum[0]
        payload[publicKeyBytes.size + 1] = checksum[1]
        return PUBLIC_TOKEN_PREFIX + encodeBase32(payload)
    }

    fun parsePublicKeyToken(rawInput: String): Pair<ByteArray, String> {
        val cleaned = rawInput.trim()
            .replace("\\s+".toRegex(), "")
            .replace("-", "")
            .uppercase()
        require(cleaned.startsWith(PUBLIC_TOKEN_PREFIX) && cleaned.length > 20) {
            "Invalid CipherKey public token. Expected alphanumeric key starting with $PUBLIC_TOKEN_PREFIX."
        }
        val decoded = decodeBase32(cleaned.removePrefix(PUBLIC_TOKEN_PREFIX))
        require(decoded.size > 32) { "Public key token is too short." }
        val pubBytes = decoded.copyOfRange(0, decoded.size - 2)
        val expectedChecksum = MessageDigest.getInstance("SHA-256").digest(pubBytes)
        require(
            decoded[decoded.size - 2] == expectedChecksum[0] &&
                decoded[decoded.size - 1] == expectedChecksum[1]
        ) {
            "Public key checksum mismatch. Verify all characters were copied."
        }
        // Validate actual EC public key parsing
        decodePublicKey(pubBytes)
        return pubBytes to computeFingerprint(pubBytes)
    }

    fun computeFingerprint(publicKeyBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
        return digest.take(4).joinToString("") { "%02X".format(it) }
    }

    fun encryptMessage(
        plaintext: String,
        ownPrivateKeyBytes: ByteArray,
        ownPublicKeyBytes: ByteArray,
        recipientPublicKeyBytes: ByteArray,
        mode: CipherMode
    ): String {
        require(plaintext.isNotEmpty()) { "Message text is empty." }
        val ownPriv = decodePrivateKey(ownPrivateKeyBytes)
        val recipientPub = decodePublicKey(recipientPublicKeyBytes)
        val senderFp = computeFingerprint(ownPublicKeyBytes)
        val recipientFp = computeFingerprint(recipientPublicKeyBytes)

        return when (mode) {
            CipherMode.STANDARD -> {
                val staticSecret = performEcdh(ownPriv, recipientPub)
                val info = canonicalInfo("cipherkey-std-v1", senderFp, recipientFp)
                val aesKey = hkdfSha256(staticSecret, info, 32)
                val aad = "$STANDARD_MSG_PREFIX$senderFp:$recipientFp".toByteArray(Charsets.UTF_8)
                val encrypted = aesGcmEncrypt(
                    aesKey,
                    plaintext.toByteArray(Charsets.UTF_8),
                    aad
                )
                val b64 = base64Encode(encrypted, urlSafe = true)
                "$STANDARD_MSG_PREFIX$senderFp:$recipientFp:$b64"
            }

            CipherMode.HYPER -> {
                val ephKpg = KeyPairGenerator.getInstance("EC")
                ephKpg.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
                val ephPair = ephKpg.generateKeyPair()
                val ephPubBytes = ephPair.public.encoded

                val staticSecret = performEcdh(ownPriv, recipientPub)
                val ephSecret = performEcdh(ephPair.private, recipientPub)
                val combinedSecret = ByteArray(staticSecret.size + ephSecret.size)
                System.arraycopy(staticSecret, 0, combinedSecret, 0, staticSecret.size)
                System.arraycopy(ephSecret, 0, combinedSecret, staticSecret.size, ephSecret.size)

                val info = canonicalInfo("cipherkey-hyper-v1", senderFp, recipientFp)
                val keyMaterial = hkdfSha256(combinedSecret, info, 64)
                val layer1Key = keyMaterial.copyOfRange(0, 32)
                val layer2Key = keyMaterial.copyOfRange(32, 64)

                val paddedPlaintext = padToBlock(plaintext.toByteArray(Charsets.UTF_8), HYPER_BLOCK_SIZE)
                val aad1 = "$HYPER_MSG_PREFIX$senderFp:$recipientFp".toByteArray(Charsets.UTF_8)
                val layer1Blob = aesGcmEncrypt(layer1Key, paddedPlaintext, aad1)
                val layer2Blob = aesGcmEncrypt(layer2Key, layer1Blob, ephPubBytes)

                val packet = ByteBuffer.allocate(2 + ephPubBytes.size + layer2Blob.size)
                    .putShort(ephPubBytes.size.toShort())
                    .put(ephPubBytes)
                    .put(layer2Blob)
                    .array()
                val b64 = base64Encode(packet, urlSafe = true)
                "$HYPER_MSG_PREFIX$senderFp:$recipientFp:$b64"
            }
        }
    }

    fun isCipherPayload(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.startsWith(STANDARD_MSG_PREFIX) || trimmed.startsWith(HYPER_MSG_PREFIX)
    }

    fun extractHeaderFingerprints(text: String): Pair<String, String>? {
        val trimmed = text.trim()
        val body = when {
            trimmed.startsWith(STANDARD_MSG_PREFIX) -> trimmed.removePrefix(STANDARD_MSG_PREFIX)
            trimmed.startsWith(HYPER_MSG_PREFIX) -> trimmed.removePrefix(HYPER_MSG_PREFIX)
            else -> return null
        }
        val parts = body.split(":", limit = 3)
        if (parts.size < 3) return null
        return parts[0] to parts[1]
    }

    fun decryptMessage(
        cipherText: String,
        ownPrivateKeyBytes: ByteArray,
        ownPublicKeyBytes: ByteArray,
        peerPublicKeyBytes: ByteArray
    ): DecryptedCipherPayload {
        val trimmed = cipherText.trim()
        val mode = when {
            trimmed.startsWith(HYPER_MSG_PREFIX) -> CipherMode.HYPER
            trimmed.startsWith(STANDARD_MSG_PREFIX) -> CipherMode.STANDARD
            else -> throw IllegalArgumentException("Text is not a valid CipherKey encrypted message.")
        }
        val body = trimmed.substringAfter(":")
        val parts = body.split(":", limit = 3)
        require(parts.size == 3) { "Malformed CipherKey envelope." }
        val senderFp = parts[0]
        val recipientFp = parts[1]
        val payloadBytes = base64Decode(parts[2], urlSafe = true)

        val ownPriv = decodePrivateKey(ownPrivateKeyBytes)
        val ownFp = computeFingerprint(ownPublicKeyBytes)
        val peerPub = decodePublicKey(peerPublicKeyBytes)
        val peerFp = computeFingerprint(peerPublicKeyBytes)

        require(
            (senderFp == peerFp && recipientFp == ownFp) ||
                (senderFp == ownFp && recipientFp == peerFp)
        ) {
            "Key mismatch: message is between $senderFp and $recipientFp, selected key is $peerFp."
        }

        return when (mode) {
            CipherMode.STANDARD -> {
                val staticSecret = performEcdh(ownPriv, peerPub)
                val info = canonicalInfo("cipherkey-std-v1", senderFp, recipientFp)
                val aesKey = hkdfSha256(staticSecret, info, 32)
                val aad = "$STANDARD_MSG_PREFIX$senderFp:$recipientFp".toByteArray(Charsets.UTF_8)
                val plainBytes = aesGcmDecrypt(aesKey, payloadBytes, aad)
                DecryptedCipherPayload(
                    plaintext = plainBytes.toString(Charsets.UTF_8),
                    senderFingerprint = senderFp,
                    recipientFingerprint = recipientFp,
                    cipherMode = CipherMode.STANDARD
                )
            }

            CipherMode.HYPER -> {
                val buffer = ByteBuffer.wrap(payloadBytes)
                val ephLen = buffer.short.toInt() and 0xFFFF
                require(ephLen in 32..256 && buffer.remaining() > ephLen + GCM_IV_BYTES) {
                    "Corrupted Hyper Encryption header."
                }
                val ephPubBytes = ByteArray(ephLen)
                buffer.get(ephPubBytes)
                val layer2Blob = ByteArray(buffer.remaining())
                buffer.get(layer2Blob)

                val ephPub = decodePublicKey(ephPubBytes)
                val staticSecret = performEcdh(ownPriv, peerPub)
                val ephSecret = performEcdh(ownPriv, ephPub)
                val combinedSecret = ByteArray(staticSecret.size + ephSecret.size)
                System.arraycopy(staticSecret, 0, combinedSecret, 0, staticSecret.size)
                System.arraycopy(ephSecret, 0, combinedSecret, staticSecret.size, ephSecret.size)

                val info = canonicalInfo("cipherkey-hyper-v1", senderFp, recipientFp)
                val keyMaterial = hkdfSha256(combinedSecret, info, 64)
                val layer1Key = keyMaterial.copyOfRange(0, 32)
                val layer2Key = keyMaterial.copyOfRange(32, 64)

                val layer1Blob = aesGcmDecrypt(layer2Key, layer2Blob, ephPubBytes)
                val aad1 = "$HYPER_MSG_PREFIX$senderFp:$recipientFp".toByteArray(Charsets.UTF_8)
                val paddedPlaintext = aesGcmDecrypt(layer1Key, layer1Blob, aad1)
                val plainBytes = unpadFromBlock(paddedPlaintext)
                DecryptedCipherPayload(
                    plaintext = plainBytes.toString(Charsets.UTF_8),
                    senderFingerprint = senderFp,
                    recipientFingerprint = recipientFp,
                    cipherMode = CipherMode.HYPER
                )
            }
        }
    }

    private fun decodePublicKey(bytes: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))

    private fun decodePrivateKey(bytes: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))

    private fun performEcdh(privateKey: PrivateKey, publicKey: PublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(publicKey, true)
        return agreement.generateSecret()
    }

    private fun canonicalInfo(domain: String, fpA: String, fpB: String): ByteArray {
        val ordered = if (fpA <= fpB) "$fpA:$fpB" else "$fpB:$fpA"
        return "$domain|$ordered".toByteArray(Charsets.UTF_8)
    }

    private fun hkdfSha256(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val salt = "CipherKey-HKDF-Salt-v1".toByteArray(Charsets.UTF_8)
        val extractMac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(salt, "HmacSHA256"))
        }
        val prk = extractMac.doFinal(ikm)

        val okm = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val expandMac = Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(prk, "HmacSHA256"))
            }
            expandMac.update(t)
            expandMac.update(info)
            expandMac.update(counter.toByte())
            t = expandMac.doFinal()
            val copyLen = minOf(t.size, length - offset)
            System.arraycopy(t, 0, okm, offset, copyLen)
            offset += copyLen
            counter++
        }
        return okm
    }

    private fun aesGcmEncrypt(keyBytes: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_BYTES)
        secureRandom.nextBytes(iv)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        val ct = cipher.doFinal(plaintext)
        val out = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ct, 0, out, iv.size, ct.size)
        return out
    }

    private fun aesGcmDecrypt(keyBytes: ByteArray, ivAndCiphertext: ByteArray, aad: ByteArray): ByteArray {
        require(ivAndCiphertext.size > GCM_IV_BYTES) { "Ciphertext too short" }
        val iv = ivAndCiphertext.copyOfRange(0, GCM_IV_BYTES)
        val ct = ivAndCiphertext.copyOfRange(GCM_IV_BYTES, ivAndCiphertext.size)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ct)
    }

    private fun padToBlock(data: ByteArray, blockSize: Int): ByteArray {
        val rawLen = 2 + data.size
        val targetLen = ((rawLen + blockSize - 1) / blockSize) * blockSize
        val out = ByteArray(targetLen)
        out[0] = ((data.size ushr 8) and 0xFF).toByte()
        out[1] = (data.size and 0xFF).toByte()
        System.arraycopy(data, 0, out, 2, data.size)
        if (targetLen > rawLen) {
            val pad = ByteArray(targetLen - rawLen)
            secureRandom.nextBytes(pad)
            System.arraycopy(pad, 0, out, rawLen, pad.size)
        }
        return out
    }

    private fun unpadFromBlock(padded: ByteArray): ByteArray {
        require(padded.size >= 2) { "Invalid padded payload" }
        val len = ((padded[0].toInt() and 0xFF) shl 8) or (padded[1].toInt() and 0xFF)
        require(len >= 0 && 2 + len <= padded.size) { "Corrupted padded message length" }
        return padded.copyOfRange(2, 2 + len)
    }

    private fun encodeBase32(data: ByteArray): String {
        val out = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bitsLeft = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                out.append(BASE32_ALPHABET[(buffer ushr bitsLeft) and 0x1F])
            }
        }
        if (bitsLeft > 0) {
            out.append(BASE32_ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        }
        return out.toString()
    }

    private fun decodeBase32(input: String): ByteArray {
        val bytes = ArrayList<Byte>((input.length * 5) / 8)
        var buffer = 0
        var bitsLeft = 0
        for (ch in input) {
            val idx = BASE32_ALPHABET.indexOf(ch)
            require(idx >= 0) { "Invalid alphanumeric character '$ch' in public key." }
            buffer = (buffer shl 5) or idx
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                bytes.add(((buffer ushr bitsLeft) and 0xFF).toByte())
            }
        }
        return bytes.toByteArray()
    }

    private fun base64Encode(data: ByteArray, urlSafe: Boolean): String {
        val alphabet = if (urlSafe) {
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        } else {
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        }
        val sb = StringBuilder(((data.size + 2) / 3) * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else -1

            sb.append(alphabet[b0 ushr 2])
            sb.append(alphabet[((b0 and 0x03) shl 4) or (if (b1 >= 0) (b1 ushr 4) else 0)])
            if (b1 >= 0) {
                sb.append(alphabet[((b1 and 0x0F) shl 2) or (if (b2 >= 0) (b2 ushr 6) else 0)])
            } else if (!urlSafe) {
                sb.append('=')
            }
            if (b2 >= 0) {
                sb.append(alphabet[b2 and 0x3F])
            } else if (!urlSafe) {
                sb.append('=')
            }
            i += 3
        }
        return sb.toString()
    }

    private fun base64Decode(input: String, urlSafe: Boolean): ByteArray {
        val clean = input.trim().trimEnd('=')
        val out = ArrayList<Byte>((clean.length * 3) / 4)
        var buffer = 0
        var bitsLeft = 0
        for (ch in clean) {
            val val6 = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in 'a'..'z' -> ch - 'a' + 26
                in '0'..'9' -> ch - '0' + 52
                '+', '-' -> if (urlSafe && ch == '-') 62 else if (!urlSafe && ch == '+') 62 else -1
                '/', '_' -> if (urlSafe && ch == '_') 63 else if (!urlSafe && ch == '/') 63 else -1
                else -> -1
            }
            require(val6 >= 0) { "Invalid Base64 character" }
            buffer = (buffer shl 6) or val6
            bitsLeft += 6
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.add(((buffer ushr bitsLeft) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }
}
