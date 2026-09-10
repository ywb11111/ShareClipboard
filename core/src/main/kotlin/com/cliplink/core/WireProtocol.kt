package com.cliplink.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

data class ClipMessage(
    val id: String,
    val originDeviceId: String,
    val originDeviceName: String,
    val timestamp: Long,
    val text: String,
    val kind: MessageKind = MessageKind.CLIPBOARD,
    val originPort: Int = 24816,
)

enum class MessageKind { CLIPBOARD, HELLO, HELLO_ACK }

object WireProtocol {
    const val MAGIC = 0x434C4E4B // CLNK
    const val VERSION = 2
    const val MAX_ENCRYPTED_BYTES = 1_100_000
    const val MAX_TEXT_BYTES = 1_000_000

    fun encode(message: ClipMessage): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(VERSION)
            output.writeByte(message.kind.ordinal)
            output.writeUTF(message.id)
            output.writeUTF(message.originDeviceId)
            output.writeUTF(message.originDeviceName.take(64))
            output.writeInt(message.originPort)
            output.writeLong(message.timestamp)
            val text = message.text.toByteArray(StandardCharsets.UTF_8)
            require(text.size <= MAX_TEXT_BYTES) { "剪贴板内容超过 1 MB" }
            output.writeInt(text.size)
            output.write(text)
        }
        bytes.toByteArray()
    }

    fun decode(payload: ByteArray): ClipMessage = DataInputStream(ByteArrayInputStream(payload)).use { input ->
        require(input.readInt() == VERSION) { "不支持的协议版本" }
        val kind = MessageKind.entries.getOrNull(input.readUnsignedByte())
            ?: error("不支持的消息类型")
        val id = input.readUTF()
        val originDeviceId = input.readUTF()
        val originDeviceName = input.readUTF()
        val originPort = input.readInt()
        require(originPort in 1..65535) { "无效的来源端口" }
        val timestamp = input.readLong()
        val textSize = input.readInt()
        require(textSize in 0..MAX_TEXT_BYTES) { "无效的文本长度" }
        val textBytes = ByteArray(textSize)
        input.readFully(textBytes)
        val text = String(textBytes, StandardCharsets.UTF_8)
        ClipMessage(id, originDeviceId, originDeviceName, timestamp, text, kind, originPort)
    }
}
