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
    val content: ClipboardContent? = null,
    val kind: MessageKind = MessageKind.CLIPBOARD,
    val originPort: Int = 24816,
)

enum class MessageKind { CLIPBOARD, HELLO, HELLO_ACK }

object WireProtocol {
    const val MAGIC = 0x434C4E4B // CLNK
    const val VERSION = 3
    const val MAX_CONTENT_BYTES = 32 * 1024 * 1024
    const val MAX_ENCRYPTED_BYTES = MAX_CONTENT_BYTES + 256 * 1024
    const val MAX_FILES = 32
    private const val MAX_FIELD_BYTES = MAX_CONTENT_BYTES

    fun encode(message: ClipMessage): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(VERSION)
            output.writeByte(message.kind.ordinal)
            output.writeUTF(message.id)
            output.writeUTF(message.originDeviceId)
            output.writeUTF(message.originDeviceName.take(64))
            output.writeInt(message.originPort)
            output.writeLong(message.timestamp)
            output.writeBoolean(message.content != null)
            message.content?.let { writeContent(output, it) }
        }
        require(bytes.size() <= MAX_CONTENT_BYTES + 128 * 1024) { "剪贴板内容超过 32 MB" }
        bytes.toByteArray()
    }

    fun decode(payload: ByteArray): ClipMessage = DataInputStream(ByteArrayInputStream(payload)).use { input ->
        require(input.readInt() == VERSION) { "不支持的协议版本" }
        val kind = MessageKind.entries.getOrNull(input.readUnsignedByte()) ?: error("不支持的消息类型")
        val id = input.readUTF()
        val originDeviceId = input.readUTF()
        val originDeviceName = input.readUTF()
        val originPort = input.readInt()
        require(originPort in 1..65535) { "无效的来源端口" }
        val timestamp = input.readLong()
        val content = if (input.readBoolean()) readContent(input) else null
        ClipMessage(id, originDeviceId, originDeviceName, timestamp, content, kind, originPort)
    }

    private fun writeContent(output: DataOutputStream, content: ClipboardContent) {
        require(!content.isEmpty()) { "剪贴板内容为空" }
        require(content.sizeBytes <= MAX_CONTENT_BYTES) { "剪贴板内容超过 32 MB" }
        output.writeByte(content.kind.ordinal)
        writeString(output, content.text)
        writeString(output, content.html)
        writeString(output, content.mimeType.take(255))
        writeString(output, content.fileName.take(255))
        writeBytes(output, content.bytes)
        require(content.files.size <= MAX_FILES) { "一次最多同步 $MAX_FILES 个文件" }
        output.writeInt(content.files.size)
        content.files.forEach { file ->
            writeString(output, file.name.take(255))
            writeString(output, file.mimeType.take(255))
            writeBytes(output, file.bytes)
        }
    }

    private fun readContent(input: DataInputStream): ClipboardContent {
        val contentKind = ClipboardKind.entries.getOrNull(input.readUnsignedByte()) ?: error("不支持的剪贴板类型")
        val text = readString(input)
        val html = readString(input)
        val mimeType = readString(input)
        val fileName = readString(input)
        val bytes = readBytes(input)
        val fileCount = input.readInt()
        require(fileCount in 0..MAX_FILES) { "文件数量异常" }
        var total = text.toByteArray().size.toLong() + html.toByteArray().size + bytes.size
        val files = List(fileCount) {
            val name = readString(input)
            val type = readString(input)
            val data = readBytes(input)
            total += data.size
            require(total <= MAX_CONTENT_BYTES) { "剪贴板内容超过 32 MB" }
            ClipboardFile(name, type, data)
        }
        return ClipboardContent(contentKind, text, html, mimeType, fileName, bytes, files).also {
            require(!it.isEmpty() && it.sizeBytes <= MAX_CONTENT_BYTES) { "剪贴板内容无效" }
        }
    }

    private fun writeString(output: DataOutputStream, value: String) = writeBytes(output, value.toByteArray(StandardCharsets.UTF_8))
    private fun readString(input: DataInputStream): String = String(readBytes(input), StandardCharsets.UTF_8)

    private fun writeBytes(output: DataOutputStream, value: ByteArray) {
        require(value.size <= MAX_FIELD_BYTES) { "剪贴板字段过大" }
        output.writeInt(value.size)
        output.write(value)
    }

    private fun readBytes(input: DataInputStream): ByteArray {
        val size = input.readInt()
        require(size in 0..MAX_FIELD_BYTES) { "剪贴板字段长度异常" }
        return ByteArray(size).also(input::readFully)
    }
}
