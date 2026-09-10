package com.cliplink.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LanSyncService(
    private val config: Config,
    private val listener: Listener,
) : AutoCloseable {
    data class Config(
        val deviceId: String,
        val deviceName: String,
        val deviceKind: DeviceKind,
        val pairingCode: String,
        val discoveryPort: Int = 24815,
        val transferPort: Int = 24816,
    )

    interface Listener {
        fun onPeersChanged(peers: List<PeerDevice>)
        fun onMessage(message: ClipMessage)
        fun onStatus(message: String)
        fun onError(message: String)
    }

    private data class Endpoint(val device: PeerDevice, val port: Int, val manual: Boolean = false)

    private val running = AtomicBoolean(false)
    private val peers = ConcurrentHashMap<String, Endpoint>()
    private val seenMessageIds = CopyOnWriteArraySet<String>()
    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(5) { task ->
        Thread(task, "cliplink-lan").apply { isDaemon = true }
    }
    private val key = CryptoBox.deriveKey(config.pairingCode)
    private val fingerprint = CryptoBox.fingerprint(key)
    @Volatile private var udpSocket: DatagramSocket? = null
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var pendingMessage: ByteArray? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor.execute(::acceptLoop)
        executor.execute(::discoveryReceiveLoop)
        executor.scheduleAtFixedRate(::announce, 200, 2_000, TimeUnit.MILLISECONDS)
        executor.scheduleAtFixedRate(::removeExpiredPeers, 3_000, 3_000, TimeUnit.MILLISECONDS)
        executor.scheduleAtFixedRate(::refreshManualPeers, 4_000, 4_000, TimeUnit.MILLISECONDS)
        listener.onStatus("正在局域网中查找设备")
    }

    fun addManualPeer(target: String): Boolean {
        val trimmed = target.trim()
        val match = Regex("^([0-9]{1,3}(?:\\.[0-9]{1,3}){3})(?::([0-9]{1,5}))?$").matchEntire(trimmed)
            ?: return false
        val host = match.groupValues[1]
        val port = match.groupValues[2].takeIf { it.isNotBlank() }?.toIntOrNull() ?: config.transferPort
        if (port !in 1..65535) return false
        val address = runCatching { InetAddress.getByName(host) as? Inet4Address }.getOrNull() ?: return false
        val canonicalHost = address.hostAddress
        val endpoint = Endpoint(
            PeerDevice("manual:$canonicalHost:$port", canonicalHost, oppositeDeviceKind(), canonicalHost, System.currentTimeMillis()),
            port,
            manual = true,
        )
        listener.onStatus("正在连接 $host:$port")
        executor.execute { send(endpoint, encryptedMessage(MessageKind.HELLO), expectResponses = true) }
        return true
    }

    fun broadcast(content: ClipboardContent): Int {
        if (!running.get() || content.isEmpty()) return 0
        val message = ClipMessage(
            id = UUID.randomUUID().toString(),
            originDeviceId = config.deviceId,
            originDeviceName = config.deviceName,
            timestamp = System.currentTimeMillis(),
            content = content,
            originPort = config.transferPort,
        )
        val encrypted = try {
            CryptoBox.encrypt(key, WireProtocol.encode(message))
        } catch (error: Exception) {
            listener.onError(error.message ?: "无法加密剪贴板")
            return 0
        }
        val recipients = peers.values.toList()
        pendingMessage = encrypted
        recipients.forEach { endpoint ->
            executor.execute {
                if (send(endpoint, encrypted) && pendingMessage === encrypted) pendingMessage = null
            }
        }
        return recipients.size
    }

    private fun acceptLoop() {
        try {
            val server = ServerSocket().also {
                it.reuseAddress = true
                it.bind(InetSocketAddress(config.transferPort))
                serverSocket = it
            }
            while (running.get()) {
                val socket = server.accept()
                executor.execute { receive(socket) }
            }
        } catch (error: Exception) {
            if (running.get()) listener.onError("同步端口 ${config.transferPort} 启动失败：${error.message}")
        }
    }

    private fun receive(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 30_000
                val input = DataInputStream(client.getInputStream())
                val message = readMessage(input)
                if (handleIncoming(message, client.inetAddress.hostAddress) && message.kind == MessageKind.HELLO) {
                    val output = DataOutputStream(client.getOutputStream())
                    writeFrame(output, encryptedMessage(MessageKind.HELLO_ACK))
                    pendingMessage?.let { queued ->
                        writeFrame(output, queued)
                        if (pendingMessage === queued) pendingMessage = null
                    }
                }
            } catch (_: Exception) {
                // Wrong pairing keys and malformed traffic are intentionally ignored.
            }
        }
    }

    private fun send(
        endpoint: Endpoint,
        encrypted: ByteArray,
        expectResponses: Boolean = false,
        reportFailure: Boolean = true,
    ): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(endpoint.device.address, endpoint.port), 2_500)
                socket.soTimeout = 30_000
                writeFrame(DataOutputStream(socket.getOutputStream()), encrypted)
                if (expectResponses) {
                    val input = DataInputStream(socket.getInputStream())
                    while (true) {
                        val message = try { readMessage(input) } catch (_: EOFException) { break }
                        handleIncoming(message, socket.inetAddress.hostAddress)
                    }
                }
            }
            true
        } catch (error: Exception) {
            if (reportFailure) listener.onError("连接 ${endpoint.device.name} 失败：${error.message}")
            false
        }
    }

    private fun writeFrame(output: DataOutputStream, encrypted: ByteArray) {
        output.writeInt(WireProtocol.MAGIC)
        output.writeInt(encrypted.size)
        output.write(encrypted)
        output.flush()
    }

    private fun readMessage(input: DataInputStream): ClipMessage {
        require(input.readInt() == WireProtocol.MAGIC) { "不是 ClipLink 消息" }
        val size = input.readInt()
        require(size in 1..WireProtocol.MAX_ENCRYPTED_BYTES) { "消息长度异常" }
        val encrypted = ByteArray(size)
        input.readFully(encrypted)
        return WireProtocol.decode(CryptoBox.decrypt(key, encrypted))
    }

    private fun handleIncoming(message: ClipMessage, address: String): Boolean {
        if (message.originDeviceId == config.deviceId || !seenMessageIds.add(message.id)) return false
        trimSeenMessages()
        registerInboundPeer(message, address)
        when (message.kind) {
            MessageKind.CLIPBOARD -> if (message.content?.isEmpty() == false) listener.onMessage(message)
            MessageKind.HELLO -> Unit
            MessageKind.HELLO_ACK -> listener.onStatus("已通过 IP 连接 ${message.originDeviceName}")
        }
        return true
    }

    private fun discoveryReceiveLoop() {
        try {
            val socket = DatagramSocket(null).also {
                it.reuseAddress = true
                it.broadcast = true
                it.bind(InetSocketAddress(config.discoveryPort))
                it.soTimeout = 3_000
                udpSocket = it
            }
            val buffer = ByteArray(2_048)
            while (running.get()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    parseAnnouncement(String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8), packet.address)
                } catch (_: java.net.SocketTimeoutException) {
                    // Gives close() a chance to stop the loop.
                }
            }
        } catch (error: Exception) {
            if (running.get()) listener.onError("设备发现启动失败：${error.message}")
        }
    }

    private fun announce() {
        if (!running.get()) return
        val encodedName = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(config.deviceName.toByteArray(StandardCharsets.UTF_8))
        val text = listOf(
            "CLIPLINK", WireProtocol.VERSION.toString(), config.deviceId, encodedName,
            config.deviceKind.name, config.transferPort.toString(), fingerprint,
        ).joinToString("|")
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        try {
            val socket = udpSocket ?: DatagramSocket().apply { broadcast = true }
            broadcastAddresses().forEach { address ->
                try {
                    socket.send(DatagramPacket(bytes, bytes.size, address, config.discoveryPort))
                } catch (_: Exception) { }
            }
        } catch (error: Exception) {
            if (running.get()) listener.onError("无法广播设备：${error.message}")
        }
    }

    private fun parseAnnouncement(text: String, source: InetAddress) {
        val parts = text.split('|')
        if (parts.size != 7 || parts[0] != "CLIPLINK" || parts[1] != WireProtocol.VERSION.toString()) return
        if (parts[2] == config.deviceId || parts[6] != fingerprint) return
        val name = try {
            String(Base64.getUrlDecoder().decode(parts[3]), StandardCharsets.UTF_8).take(64)
        } catch (_: Exception) { return }
        val kind = runCatching { DeviceKind.valueOf(parts[4]) }.getOrNull() ?: return
        val port = parts[5].toIntOrNull()?.takeIf { it in 1..65535 } ?: return
        val device = PeerDevice(parts[2], name, kind, source.hostAddress, System.currentTimeMillis())
        val previous = peers[device.id]
        val wasNew = peers.put(device.id, Endpoint(device, port, previous?.manual == true)) == null
        publishPeers()
        if (wasNew) {
            listener.onStatus("已连接 ${device.name}")
            pendingMessage?.let { encrypted ->
                executor.execute {
                    if (send(Endpoint(device, port), encrypted) && pendingMessage === encrypted) pendingMessage = null
                }
            }
        }
    }

    private fun broadcastAddresses(): Set<InetAddress> {
        val result = linkedSetOf(InetAddress.getByName("255.255.255.255"))
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                if (!network.isUp || network.isLoopback) continue
                network.interfaceAddresses.mapNotNullTo(result) { it.broadcast }
            }
        } catch (_: SocketException) { }
        return result
    }

    private fun removeExpiredPeers() {
        val cutoff = System.currentTimeMillis() - 10_000
        var removed = false
        peers.entries.forEach { entry ->
            if (entry.value.device.lastSeenAt < cutoff && peers.remove(entry.key, entry.value)) removed = true
        }
        if (removed) {
            publishPeers()
            if (peers.isEmpty()) listener.onStatus("正在局域网中查找设备")
        }
    }

    private fun publishPeers() = listener.onPeersChanged(
        peers.values.map { it.device }.sortedBy { it.name.lowercase() },
    )

    private fun trimSeenMessages() {
        if (seenMessageIds.size > 1_000) seenMessageIds.clear()
    }

    private fun registerInboundPeer(message: ClipMessage, address: String): Endpoint {
        peers.entries.forEach { entry ->
            if (entry.key.startsWith("manual:") && entry.value.device.address == address) peers.remove(entry.key, entry.value)
        }
        val device = PeerDevice(
            message.originDeviceId,
            message.originDeviceName,
            oppositeDeviceKind(),
            address,
            System.currentTimeMillis(),
        )
        return Endpoint(device, message.originPort, manual = true).also {
            peers[device.id] = it
            publishPeers()
        }
    }

    private fun refreshManualPeers() {
        if (!running.get()) return
        peers.values.filter { it.manual }.forEach { endpoint ->
            executor.execute {
                send(endpoint, encryptedMessage(MessageKind.HELLO), expectResponses = true, reportFailure = false)
            }
        }
    }

    private fun encryptedMessage(kind: MessageKind): ByteArray {
        val message = ClipMessage(
            id = UUID.randomUUID().toString(),
            originDeviceId = config.deviceId,
            originDeviceName = config.deviceName,
            timestamp = System.currentTimeMillis(),
            content = null,
            kind = kind,
            originPort = config.transferPort,
        )
        return CryptoBox.encrypt(key, WireProtocol.encode(message))
    }

    private fun oppositeDeviceKind(): DeviceKind = if (config.deviceKind == DeviceKind.PHONE) {
        DeviceKind.DESKTOP
    } else {
        DeviceKind.PHONE
    }

    companion object {
        fun localIpv4Addresses(): List<String> {
            val addresses = linkedSetOf<String>()
            runCatching {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val network = interfaces.nextElement()
                    if (!network.isUp || network.isLoopback) continue
                    val items = network.inetAddresses
                    while (items.hasMoreElements()) {
                        val address = items.nextElement()
                        if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                            addresses += address.hostAddress
                        }
                    }
                }
            }
            return addresses.toList()
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { udpSocket?.close() }
        runCatching { serverSocket?.close() }
        executor.shutdownNow()
    }
}
