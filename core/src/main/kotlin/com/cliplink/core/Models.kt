package com.cliplink.core

enum class DeviceKind { PHONE, DESKTOP }

enum class ClipDirection { SENT, RECEIVED }

data class PeerDevice(
    val id: String,
    val name: String,
    val kind: DeviceKind,
    val address: String,
    val lastSeenAt: Long,
)

data class ClipItem(
    val id: String,
    val text: String,
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
    val currentClipboard: String = "",
    val autoSend: Boolean = true,
    val autoReceive: Boolean = true,
    val isRunning: Boolean = false,
    val statusMessage: String = "正在启动…",
    val errorMessage: String? = null,
)

interface ClipboardPort {
    fun readText(): String?
    fun writeText(text: String)
    fun startWatching(onChanged: (String) -> Unit)
    fun stopWatching()
}

interface SettingsStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}
