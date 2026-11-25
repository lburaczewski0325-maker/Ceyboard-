package com.example

import com.example.crypto.CipherMode
import com.example.crypto.KeystoreCryptoEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoEngineUnitTest {

    private val engine = KeystoreCryptoEngine()

    @Test
    fun `alphanumeric key swap and hyper encryption roundtrip succeeds between two users`() {
        val alice = engine.generateIdentity()
        val bob = engine.generateIdentity()

        val (bobPubFromToken, bobFpFromToken) = engine.parsePublicKeyToken(bob.publicKeyToken)
        assertEquals(bob.fingerprint, bobFpFromToken)

        val (alicePubFromToken, aliceFpFromToken) = engine.parsePublicKeyToken(alice.publicKeyToken)
        assertEquals(alice.fingerprint, aliceFpFromToken)

        val secret = "Meet at 19:30 near the north gate!"
        val hyperCipher = engine.encryptMessage(
            plaintext = secret,
            ownPrivateKeyBytes = alice.privateKeyBytes,
            ownPublicKeyBytes = alice.publicKeyBytes,
            recipientPublicKeyBytes = bobPubFromToken,
            mode = CipherMode.HYPER
        )

        assertTrue(hyperCipher.startsWith("CKH1:"))
        assertNotEquals(secret, hyperCipher)

        val decrypted = engine.decryptMessage(
            cipherText = hyperCipher,
            ownPrivateKeyBytes = bob.privateKeyBytes,
            ownPublicKeyBytes = bob.publicKeyBytes,
            peerPublicKeyBytes = alicePubFromToken
        )

        assertEquals(secret, decrypted.plaintext)
        assertEquals(CipherMode.HYPER, decrypted.cipherMode)
        assertEquals(alice.fingerprint, decrypted.senderFingerprint)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `third party key cannot decrypt swapped message`() {
        val alice = engine.generateIdentity()
        val bob = engine.generateIdentity()
        val eve = engine.generateIdentity()

        val cipher = engine.encryptMessage(
            plaintext = "Confidential",
            ownPrivateKeyBytes = alice.privateKeyBytes,
            ownPublicKeyBytes = alice.publicKeyBytes,
            recipientPublicKeyBytes = bob.publicKeyBytes,
            mode = CipherMode.STANDARD
        )

        engine.decryptMessage(
            cipherText = cipher,
            ownPrivateKeyBytes = eve.privateKeyBytes,
            ownPublicKeyBytes = eve.publicKeyBytes,
            peerPublicKeyBytes = alice.publicKeyBytes
        )
    }

    @Test
    fun `at-rest encryption protects stored vault strings`() {
        val rawTag = "Partner-Alpha-Key"
        val encrypted = engine.encryptStringAtRest(rawTag)
        assertNotEquals(rawTag, encrypted)
        assertEquals(rawTag, engine.decryptStringAtRest(encrypted))
    }
}
