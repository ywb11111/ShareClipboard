package com.cliplink.core

import java.security.MessageDigest

enum class DeviceKind { PHONE, DESKTOP }
enum class ClipDirection { SENT, RECEIVED }
enum class ClipboardKind { TEXT, HTML, IMAGE, FILES }

data class ClipboardFile(
    val name: String,
    val mimeType: String = "application/octet-stream",
    val bytes: ByteArray,
)

data class ClipboardContent(
    val kind: ClipboardKind,
    val text: String = "",
    val html: String = "",
    val mimeType: String = "",
    val fileName: String = "",
    val bytes: ByteArray = byteArrayOf(),
    val files: List<ClipboardFile> = emptyList(),
) {
    val sizeBytes: Long
        get() = text.toByteArray().size.toLong() + html.toByteArray().size + bytes.size +
            files.sumOf { it.bytes.size.toLong() }

    fun isEmpty(): Boolean = when (kind) {
        ClipboardKind.TEXT -> text.isEmpty()
        ClipboardKind.HTML -> text.isEmpty() && html.isEmpty()
        ClipboardKind.IMAGE -> bytes.isEmpty()
        ClipboardKind.FILES -> files.isEmpty()
    }

    fun summary(): String = when (kind) {
        ClipboardKind.TEXT -> text.ifBlank { "空文本" }
        ClipboardKind.HTML -> text.ifBlank { "富文本 (${formatBytes(sizeBytes)})" }
        ClipboardKind.IMAGE -> "图片 · ${fileName.ifBlank { mimeType.ifBlank { "PNG" } }} · ${formatBytes(sizeBytes)}"
        ClipboardKind.FILES -> when (files.size) {
            0 -> "空文件列表"
            1 -> "文件 · ${files.first().name} · ${formatBytes(sizeBytes)}"
            else -> "${files.size} 个文件 · ${formatBytes(sizeBytes)}"
        }
    }

    fun fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) = digest.update(value.toByteArray())
        add(kind.name); add(text); add(html); add(mimeType); add(fileName); digest.update(bytes)
        files.forEach { add(it.name); add(it.mimeType); digest.update(it.bytes) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun plainText(text: String) = ClipboardContent(ClipboardKind.TEXT, text = text)
        fun formatBytes(bytes: Long): String = when {
            bytes < 1_024 -> "$bytes B"
            bytes < 1_048_576 -> "%.1f KB".format(bytes / 1_024.0)
            else -> "%.1f MB".format(bytes / 1_048_576.0)
        }
    }
}

data class PeerDevice(
    val id: String,
    val name: String,
    val kind: DeviceKind,
    val address: String,
    val lastSeenAt: Long,
)

data class ClipItem(
    val id: String,
    val content: ClipboardContent,
    val direction: ClipDirection,
    val deviceName: String,
    val timestamp: Long,
)

data class AppState(
    val deviceName: String,
    val pairingCode: String,
    val localAddresses: List<String> = emptyList(),
    val peers: List<PeerDevice> = emptyList(),
    val history: List<ClipItem> = emptyList(),
    val currentClipboard: ClipboardContent? = null,
    val autoSend: Boolean = true,
    val autoReceive: Boolean = true,
    val isRunning: Boolean = false,
    val statusMessage: String = "正在启动…",
    val errorMessage: String? = null,
)

interface ClipboardPort {
    fun readContent(): ClipboardContent?
    fun writeContent(content: ClipboardContent)
    fun startWatching(onChanged: (ClipboardContent) -> Unit)
    fun stopWatching()
}

interface SettingsStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}
