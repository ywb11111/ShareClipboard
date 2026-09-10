package com.cliplink.core

import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class SyncCoordinator(
    private val clipboard: ClipboardPort,
    private val settings: SettingsStore,
    defaultDeviceName: String,
    private val deviceKind: DeviceKind,
    supportsBackgroundMode: Boolean = false,
    defaultKeepAlive: Boolean = false,
    private val onKeepAliveChanged: (Boolean) -> Unit = {},
) : AutoCloseable {
    private val observers = CopyOnWriteArrayList<(AppState) -> Unit>()
    private val clipboardExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "cliplink-content").apply { isDaemon = true }
    }
    private val deviceId = settings.get(KEY_DEVICE_ID) ?: UUID.randomUUID().toString().also {
        settings.put(KEY_DEVICE_ID, it)
    }
    @Volatile private var service: LanSyncService? = null
    @Volatile private var lastPeerIds = emptySet<String>()
    private val storedPairingCode = settings.get(KEY_PAIRING_CODE)
        ?.takeIf { it.length >= 8 }
        ?: generatePairingCode().also { settings.put(KEY_PAIRING_CODE, it) }
    @Volatile private var state = AppState(
        deviceName = settings.get(KEY_DEVICE_NAME) ?: defaultDeviceName,
        pairingCode = storedPairingCode,
        autoSend = settings.get(KEY_AUTO_SEND)?.toBooleanStrictOrNull() ?: true,
        autoReceive = settings.get(KEY_AUTO_RECEIVE)?.toBooleanStrictOrNull() ?: true,
        keepAlive = settings.get(KEY_KEEP_ALIVE)?.toBooleanStrictOrNull() ?: defaultKeepAlive,
        supportsBackgroundMode = supportsBackgroundMode,
        manualTarget = settings.get(KEY_MANUAL_TARGET).orEmpty(),
    )

    fun start() {
        clipboard.startWatching(::onLocalClipboardChanged)
        update {
            it.copy(
                currentClipboard = clipboard.readContent(),
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
        update { it.copy(statusMessage = "正在读取并同步剪贴板…", errorMessage = null) }
        clipboardExecutor.execute {
            val content = clipboard.readContent()
            if (content == null || content.isEmpty()) {
                update { it.copy(errorMessage = "当前剪贴板没有可同步的文本、富文本、图片或文件") }
            } else {
                send(content)
            }
        }
    }

    fun copyToClipboard(content: ClipboardContent) {
        clipboard.writeContent(content)
        update { it.copy(currentClipboard = content, statusMessage = "已复制到本机剪贴板") }
    }

    fun deleteHistory(id: String) = update { current ->
        current.copy(history = current.history.filterNot { it.id == id })
    }

    fun clearHistory() = update { it.copy(history = emptyList()) }

    fun setAutoSend(enabled: Boolean) {
        settings.put(KEY_AUTO_SEND, enabled.toString())
        update { it.copy(autoSend = enabled) }
        if (enabled && state.peers.isNotEmpty()) syncCurrentAfterConnection()
    }

    fun setAutoReceive(enabled: Boolean) {
        settings.put(KEY_AUTO_RECEIVE, enabled.toString())
        update { it.copy(autoReceive = enabled) }
    }

    fun setKeepAlive(enabled: Boolean) {
        settings.put(KEY_KEEP_ALIVE, enabled.toString())
        update { it.copy(keepAlive = enabled) }
        onKeepAliveChanged(enabled)
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
        if (accepted) {
            settings.put(KEY_MANUAL_TARGET, target.trim())
            update { it.copy(manualTarget = target.trim(), errorMessage = null) }
        } else update { it.copy(errorMessage = "IPv4 地址格式不正确，可使用 172.23.2.80 或 172.23.2.80:24816") }
        return accepted
    }

    private fun restartService() {
        service?.close()
        lastPeerIds = emptySet()
        val current = state
        service = LanSyncService(
            config = LanSyncService.Config(deviceId, current.deviceName, deviceKind, current.pairingCode),
            listener = object : LanSyncService.Listener {
                override fun onPeersChanged(peers: List<PeerDevice>) {
                    val peerIds = peers.mapTo(mutableSetOf()) { it.id }
                    val hasNewPeer = peerIds.any { it !in lastPeerIds }
                    lastPeerIds = peerIds
                    update { it.copy(peers = peers) }
                    if (hasNewPeer && state.autoSend) syncCurrentAfterConnection()
                }
                override fun onStatus(message: String) = update { it.copy(statusMessage = message, errorMessage = null) }
                override fun onError(message: String) = update { it.copy(errorMessage = message) }
                override fun onMessage(message: ClipMessage) = onRemoteMessage(message)
                override fun onTransportState(listening: Boolean, detail: String) = update {
                    it.copy(tcpListening = listening, transportDetail = detail)
                }
            },
        ).also { newService ->
            newService.start()
            current.manualTarget.takeIf { it.isNotBlank() }?.let(newService::addManualPeer)
        }
    }

    private fun syncCurrentAfterConnection() {
        clipboardExecutor.execute {
            val content = clipboard.readContent()?.takeUnless { it.isEmpty() } ?: return@execute
            send(content, statusWhenSent = "连接成功，已自动同步当前剪贴板")
        }
    }

    private fun onLocalClipboardChanged(content: ClipboardContent) {
        if (content.isEmpty()) return
        update { it.copy(currentClipboard = content) }
        if (state.autoSend) clipboardExecutor.execute { send(content) }
    }

    private fun send(content: ClipboardContent, statusWhenSent: String? = null) {
        val recipients = service?.broadcast(content) ?: 0
        val item = ClipItem(UUID.randomUUID().toString(), content, ClipDirection.SENT, "${recipients} 台设备", System.currentTimeMillis())
        update { current ->
            current.copy(
                currentClipboard = content,
                history = trimHistory(listOf(item) + current.history),
                statusMessage = if (recipients == 0) "已排队，设备连接后自动发送" else statusWhenSent ?: "已发送到 $recipients 台设备",
                errorMessage = null,
            )
        }
    }

    private fun onRemoteMessage(message: ClipMessage) {
        val content = message.content ?: return
        val item = ClipItem(message.id, content, ClipDirection.RECEIVED, message.originDeviceName, message.timestamp)
        val shouldApply = state.autoReceive
        if (shouldApply) clipboard.writeContent(content)
        update { current ->
            current.copy(
                currentClipboard = if (shouldApply) content else current.currentClipboard,
                history = trimHistory((listOf(item) + current.history).distinctBy { it.id }),
                statusMessage = if (shouldApply) "已接收 ${message.originDeviceName} 的${kindName(content.kind)}" else "收到剪贴板内容（自动接收已关闭）",
                errorMessage = null,
            )
        }
    }

    private fun trimHistory(items: List<ClipItem>): List<ClipItem> {
        var bytes = 0L
        return items.take(MAX_HISTORY).takeWhile {
            bytes += it.content.sizeBytes
            bytes <= MAX_HISTORY_BYTES
        }
    }

    private fun update(transform: (AppState) -> AppState) {
        synchronized(this) { state = transform(state) }
        val snapshot = state
        observers.forEach { observer -> runCatching { observer(snapshot) } }
    }

    override fun close() {
        clipboard.stopWatching()
        clipboardExecutor.shutdownNow()
        service?.close()
        service = null
        lastPeerIds = emptySet()
        update { it.copy(isRunning = false, peers = emptyList(), statusMessage = "已停止") }
    }

    companion object {
        private const val MAX_HISTORY = 30
        private const val MAX_HISTORY_BYTES = 64L * 1024 * 1024
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_PAIRING_CODE = "pairing_code"
        private const val KEY_AUTO_SEND = "auto_send"
        private const val KEY_AUTO_RECEIVE = "auto_receive"
        private const val KEY_KEEP_ALIVE = "keep_alive"
        private const val KEY_MANUAL_TARGET = "manual_target"

        private fun kindName(kind: ClipboardKind) = when (kind) {
            ClipboardKind.TEXT -> "文本"
            ClipboardKind.HTML -> "富文本"
            ClipboardKind.IMAGE -> "图片"
            ClipboardKind.FILES -> "文件"
        }

        private fun generatePairingCode(): String {
            val random = SecureRandom()
            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            return List(3) {
                buildString { repeat(4) { append(alphabet[random.nextInt(alphabet.length)]) } }
            }.joinToString("-")
        }
    }
}
