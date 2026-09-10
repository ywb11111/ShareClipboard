package com.cliplink.core

import java.util.UUID
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProtocolTest {
    @Test
    fun encryptedMessageRoundTrips() {
        val content = ClipboardContent(
            ClipboardKind.FILES,
            files = listOf(ClipboardFile("你好.txt", "text/plain", "ClipLink 👋".encodeToByteArray())),
        )
        val message = ClipMessage(UUID.randomUUID().toString(), "phone-1", "Pixel", 42L, content)
        val key = CryptoBox.deriveKey("1234-5678")

        val decoded = WireProtocol.decode(CryptoBox.decrypt(key, CryptoBox.encrypt(key, WireProtocol.encode(message))))

        assertEquals(message.id, decoded.id)
        assertEquals(message.originDeviceId, decoded.originDeviceId)
        assertEquals(content.fingerprint(), decoded.content?.fingerprint())
    }

    @Test
    fun wrongPairingCodeCannotDecrypt() {
        val encrypted = CryptoBox.encrypt(CryptoBox.deriveKey("1234-5678"), "secret".encodeToByteArray())

        assertFailsWith<AEADBadTagException> {
            CryptoBox.decrypt(CryptoBox.deriveKey("8765-4321"), encrypted)
        }
    }

    @Test
    fun everySupportedClipboardKindRoundTrips() {
        val contents = listOf(
            ClipboardContent.plainText("plain text"),
            ClipboardContent(ClipboardKind.HTML, text = "Bold", html = "<b>Bold</b>", mimeType = "text/html"),
            ClipboardContent(ClipboardKind.IMAGE, mimeType = "image/png", fileName = "shot.png", bytes = byteArrayOf(1, 2, 3)),
            ClipboardContent(ClipboardKind.FILES, files = listOf(
                ClipboardFile("a.txt", "text/plain", byteArrayOf(4, 5)),
                ClipboardFile("b.pdf", "application/pdf", byteArrayOf(6, 7, 8)),
            )),
        )

        contents.forEach { content ->
            val message = ClipMessage("id-${content.kind}", "device", "name", 1L, content)
            val decoded = WireProtocol.decode(WireProtocol.encode(message))
            assertEquals(content.kind, decoded.content?.kind)
            assertEquals(content.fingerprint(), decoded.content?.fingerprint())
        }
    }

    @Test
    fun oversizedClipboardIsRejected() {
        val message = ClipMessage(
            "id", "device", "name", 1L,
            ClipboardContent(ClipboardKind.IMAGE, bytes = ByteArray(WireProtocol.MAX_CONTENT_BYTES + 1)),
        )

        assertFailsWith<IllegalArgumentException> { WireProtocol.encode(message) }
    }
}
