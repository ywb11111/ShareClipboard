package com.cliplink.core

import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class SyncCoordinator(
    private val clipboard: ClipboardPort,
    private val settings: SettingsStore,
    defaultDeviceName: String,
    private val deviceKind: DeviceKind,
) : AutoCloseable {
    private val observers = CopyOnWriteArrayList<(AppState) -> Unit>()
    private val deviceId = settings.get(KEY_DEVICE_ID) ?: UUID.randomUUID().toString().also {
        settings.put(KEY_DEVICE_ID, it)
    }
    @Volatile private var service: LanSyncService? = null
    private val storedPairingCode = settings.get(KEY_PAIRING_CODE)
        ?.takeIf { it.length >= 8 }
        ?: generatePairingCode().also { settings.put(KEY_PAIRING_CODE, it) }
    @Volatile private var state = AppState(
        deviceName = settings.get(KEY_DEVICE_NAME) ?: defaultDeviceName,
        pairingCode = storedPairingCode,
        autoSend = settings.get(KEY_AUTO_SEND)?.toBooleanStrictOrNull() ?: true,
        autoReceive = settings.get(KEY_AUTO_RECEIVE)?.toBooleanStrictOrNull() ?: true,
    )

    fun start() {
        clipboard.startWatching(::onLocalClipboardChanged)
        update {
            it.copy(
                currentClipboard = clipboard.readText().orEmpty(),
                localAddresses = LanSyncService.localIpv4Addresses(),
                isRunning = true,
            )
        }
        restartService()
    }

    fun observe(observer: (AppState) -> Unit): AutoCloseable {
        observers += observer
        observer(state)
        return AutoCloseable { observers -= observer }
    }

    fun snapshot(): AppState = state

    fun sendCurrentClipboard() {
        val text = clipboard.readText().orEmpty()
        if (text.isBlank()) {
            update { it.copy(errorMessage = "当前剪贴板没有文本") }
            return
        }
        send(text)
    }

    fun copyToClipboard(text: String) {
        clipboard.writeText(text)
        update { it.copy(currentClipboard = text, statusMessage = "已复制到本机剪贴板") }
    }

    fun deleteHistory(id: String) = update { current ->
        current.copy(history = current.history.filterNot { it.id == id })
    }

    fun clearHistory() = update { it.copy(history = emptyList()) }

    fun setAutoSend(enabled: Boolean) {
        settings.put(KEY_AUTO_SEND, enabled.toString())
        update { it.copy(autoSend = enabled) }
    }

    fun setAutoReceive(enabled: Boolean) {
        settings.put(KEY_AUTO_RECEIVE, enabled.toString())
        update { it.copy(autoReceive = enabled) }
    }

    fun updateIdentity(deviceName: String, pairingCode: String): Boolean {
        val cleanName = deviceName.trim().take(32)
        val cleanCode = pairingCode.trim().uppercase()
        if (cleanName.isBlank()) {
            update { it.copy(errorMessage = "设备名称不能为空") }
            return false
        }
        if (cleanCode.length < 8) {
            update { it.copy(errorMessage = "配对码至少需要 8 位") }
            return false
        }
        settings.put(KEY_DEVICE_NAME, cleanName)
        settings.put(KEY_PAIRING_CODE, cleanCode)
        update { it.copy(deviceName = cleanName, pairingCode = cleanCode, peers = emptyList(), errorMessage = null) }
        restartService()
        return true
    }

    fun dismissError() = update { it.copy(errorMessage = null) }

    fun connectManually(target: String): Boolean {
        if (target.isBlank()) {
            update { it.copy(errorMessage = "请输入对方设备的 IPv4 地址") }
            return false
        }
        val accepted = service?.addManualPeer(target) == true
        if (!accepted) update { it.copy(errorMessage = "IPv4 地址格式不正确，可使用 172.23.2.80 或 172.23.2.80:24816") }
        return accepted
    }

    private fun restartService() {
        service?.close()
        val current = state
        service = LanSyncService(
            config = LanSyncService.Config(deviceId, current.deviceName, deviceKind, current.pairingCode),
            listener = object : LanSyncService.Listener {
                override fun onPeersChanged(peers: List<PeerDevice>) = update { it.copy(peers = peers) }
                override fun onStatus(message: String) = update { it.copy(statusMessage = message, errorMessage = null) }
                override fun onError(message: String) = update { it.copy(errorMessage = message) }
                override fun onMessage(message: ClipMessage) = onRemoteMessage(message)
            },
        ).also { it.start() }
    }

    private fun onLocalClipboardChanged(text: String) {
        if (text.isBlank()) return
        update { it.copy(currentClipboard = text) }
        if (state.autoSend) send(text)
    }

    private fun send(text: String) {
        val recipients = service?.broadcastText(text) ?: 0
        val item = ClipItem(UUID.randomUUID().toString(), text, ClipDirection.SENT, "${recipients} 台设备", System.currentTimeMillis())
        update { current ->
            current.copy(
                currentClipboard = text,
                history = (listOf(item) + current.history).take(MAX_HISTORY),
                statusMessage = if (recipients == 0) "已排队，设备连接后自动发送" else "已发送到 $recipients 台设备",
                errorMessage = null,
            )
        }
    }

    private fun onRemoteMessage(message: ClipMessage) {
        val item = ClipItem(message.id, message.text, ClipDirection.RECEIVED, message.originDeviceName, message.timestamp)
        val shouldApply = state.autoReceive
        if (shouldApply) {
            clipboard.writeText(message.text)
        }
        update { current ->
            current.copy(
                currentClipboard = if (shouldApply) message.text else current.currentClipboard,
                history = (listOf(item) + current.history).distinctBy { it.id }.take(MAX_HISTORY),
                statusMessage = if (shouldApply) "已接收 ${message.originDeviceName} 的剪贴板" else "收到一条剪贴板（自动接收已关闭）",
                errorMessage = null,
            )
        }
    }

    private fun update(transform: (AppState) -> AppState) {
        synchronized(this) { state = transform(state) }
        val snapshot = state
        observers.forEach { observer -> runCatching { observer(snapshot) } }
    }

    override fun close() {
        clipboard.stopWatching()
        service?.close()
        service = null
        update { it.copy(isRunning = false, peers = emptyList(), statusMessage = "已停止") }
    }

    companion object {
        private const val MAX_HISTORY = 50
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_PAIRING_CODE = "pairing_code"
        private const val KEY_AUTO_SEND = "auto_send"
        private const val KEY_AUTO_RECEIVE = "auto_receive"

        private fun generatePairingCode(): String {
            val random = SecureRandom()
            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            return List(3) {
                buildString { repeat(4) { append(alphabet[random.nextInt(alphabet.length)]) } }
            }.joinToString("-")
        }
    }
}
