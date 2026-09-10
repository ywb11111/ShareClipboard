package com.cliplink.core

import java.util.UUID
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProtocolTest {
    @Test
    fun encryptedMessageRoundTrips() {
        val message = ClipMessage(UUID.randomUUID().toString(), "phone-1", "Pixel", 42L, "你好，ClipLink 👋")
        val key = CryptoBox.deriveKey("1234-5678")

        val decoded = WireProtocol.decode(CryptoBox.decrypt(key, CryptoBox.encrypt(key, WireProtocol.encode(message))))

        assertEquals(message, decoded)
    }

    @Test
    fun wrongPairingCodeCannotDecrypt() {
        val encrypted = CryptoBox.encrypt(CryptoBox.deriveKey("1234-5678"), "secret".encodeToByteArray())

        assertFailsWith<AEADBadTagException> {
            CryptoBox.decrypt(CryptoBox.deriveKey("8765-4321"), encrypted)
        }
    }

    @Test
    fun oversizedClipboardIsRejected() {
        val message = ClipMessage("id", "device", "name", 1L, "x".repeat(WireProtocol.MAX_TEXT_BYTES + 1))

        assertFailsWith<IllegalArgumentException> { WireProtocol.encode(message) }
    }
}
