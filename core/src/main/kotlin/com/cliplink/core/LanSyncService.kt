package com.cliplink.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
        fun onTransportState(listening: Boolean, detail: String) = Unit
    }

    private class Endpoint(
        @Volatile var device: PeerDevice,
        @Volatile var port: Int,
        @Volatile var heartbeatEnabled: Boolean = false,
        @Volatile var persistent: Boolean = false,
    ) {
        val heartbeatInFlight = AtomicBoolean(false)
        val consecutiveFailures = AtomicInteger(0)
        val nextHeartbeatAt = AtomicLong(0)
    }

    private val running = AtomicBoolean(false)
    private val peers = ConcurrentHashMap<String, Endpoint>()
    private val endpoints = ConcurrentHashMap<String, Endpoint>()
    private val seenMessageIds = CopyOnWriteArraySet<String>()
    private val acceptExecutor = singleThreadExecutor("cliplink-accept")
    private val discoveryExecutor = singleThreadExecutor("cliplink-discovery")
    private val ioExecutor: ExecutorService = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "cliplink-transfer").apply { isDaemon = true }
    }
    private val heartbeatExecutor: ExecutorService = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "cliplink-heartbeat").apply { isDaemon = true }
    }
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2) { task ->
        Thread(task, "cliplink-scheduler").apply { isDaemon = true }
    }
    private val key = CryptoBox.deriveKey(config.pairingCode)
    private val fingerprint = CryptoBox.fingerprint(key)
    @Volatile private var udpSocket: DatagramSocket? = null
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var pendingMessage: ByteArray? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        acceptExecutor.execute(::acceptLoop)
        discoveryExecutor.execute(::discoveryLoop)
        scheduler.scheduleAtFixedRate(::announce, 200, ANNOUNCE_INTERVAL_MS, TimeUnit.MILLISECONDS)
        scheduler.scheduleAtFixedRate(::removeExpiredPeers, 5_000, 5_000, TimeUnit.MILLISECONDS)
        scheduler.scheduleWithFixedDelay(::refreshHeartbeats, 1_000, 2_000, TimeUnit.MILLISECONDS)
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
        val endpoint = endpoints.compute(endpointKey(canonicalHost, port)) { _, existing ->
            (existing ?: Endpoint(
                PeerDevice("manual:$canonicalHost:$port", canonicalHost, oppositeDeviceKind(), canonicalHost, 0),
                port,
            )).apply {
                this.port = port
                persistent = true
                heartbeatEnabled = true
                nextHeartbeatAt.set(0)
            }
        }!!
        listener.onStatus("正在连接 $host:$port")
        runHeartbeat(endpoint, reportFailure = true)
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
        val recipients = peers.values.distinct().toList()
        pendingMessage = encrypted
        recipients.forEach { endpoint ->
            ioExecutor.execute {
                if (send(endpoint, encrypted) && pendingMessage === encrypted) pendingMessage = null
            }
        }
        return recipients.size
    }

    private fun acceptLoop() {
        var retryDelay = TCP_RETRY_MIN_MS
        while (running.get()) {
            var server: ServerSocket? = null
            try {
                server = ServerSocket().also {
                    it.reuseAddress = true
                    it.bind(InetSocketAddress(config.transferPort))
                    serverSocket = it
                }
                retryDelay = TCP_RETRY_MIN_MS
                listener.onTransportState(true, "TCP ${config.transferPort} 正在监听")
                while (running.get()) {
                    val socket = server.accept()
                    ioExecutor.execute { receive(socket) }
                }
            } catch (error: Exception) {
                if (running.get()) {
                    listener.onTransportState(false, "TCP ${config.transferPort} 启动失败，${retryDelay / 1_000} 秒后重试：${error.message}")
                }
            } finally {
                runCatching { server?.close() }
                if (serverSocket === server) serverSocket = null
            }
            if (!waitForRetry(retryDelay)) break
            retryDelay = (retryDelay * 2).coerceAtMost(TCP_RETRY_MAX_MS)
        }
    }

    private fun discoveryLoop() {
        var retryDelay = 2_000L
        while (running.get()) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null).also {
                    it.reuseAddress = true
                    it.broadcast = true
                    it.bind(InetSocketAddress(config.discoveryPort))
                    it.soTimeout = 3_000
                    udpSocket = it
                }
                retryDelay = 2_000L
                val buffer = ByteArray(2_048)
                while (running.get()) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        parseAnnouncement(String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8), packet.address)
                    } catch (_: java.net.SocketTimeoutException) {
                        // Periodically checks the running flag.
                    }
                }
            } catch (error: Exception) {
                if (running.get()) listener.onError("设备发现暂时不可用，正在重试：${error.message}")
            } finally {
                runCatching { socket?.close() }
                if (udpSocket === socket) udpSocket = null
            }
            if (!waitForRetry(retryDelay)) break
            retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
        }
    }

    private fun receive(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = SOCKET_TIMEOUT_MS
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
    ): Boolean = try {
        var responseSeen = !expectResponses
        Socket().use { socket ->
            socket.connect(InetSocketAddress(endpoint.device.address, endpoint.port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = SOCKET_TIMEOUT_MS
            writeFrame(DataOutputStream(socket.getOutputStream()), encrypted)
            if (expectResponses) {
                val input = DataInputStream(socket.getInputStream())
                while (true) {
                    val message = try { readMessage(input) } catch (_: EOFException) { break }
                    if (handleIncoming(message, socket.inetAddress.hostAddress)) responseSeen = true
                }
            }
        }
        if (!responseSeen) throw EOFException("心跳未收到响应")
        if (!expectResponses) touch(endpoint)
        true
    } catch (error: Exception) {
        if (reportFailure) listener.onError("连接 ${endpoint.device.name} 失败：${error.message}")
        false
    }

    private fun runHeartbeat(endpoint: Endpoint, reportFailure: Boolean = false) {
        if (!running.get() || !endpoint.heartbeatInFlight.compareAndSet(false, true)) return
        heartbeatExecutor.execute {
            try {
                val success = send(endpoint, encryptedMessage(MessageKind.HELLO), expectResponses = true, reportFailure = reportFailure)
                val now = System.currentTimeMillis()
                if (success) {
                    endpoint.consecutiveFailures.set(0)
                    endpoint.nextHeartbeatAt.set(now + HEARTBEAT_INTERVAL_MS)
                } else {
                    val failures = endpoint.consecutiveFailures.incrementAndGet().coerceAtMost(5)
                    val delay = (HEARTBEAT_INTERVAL_MS * (1L shl (failures - 1))).coerceAtMost(HEARTBEAT_RETRY_MAX_MS)
                    endpoint.nextHeartbeatAt.set(now + delay)
                }
            } finally {
                endpoint.heartbeatInFlight.set(false)
            }
        }
    }

    private fun refreshHeartbeats() {
        if (!running.get()) return
        val now = System.currentTimeMillis()
        endpoints.values.distinct().filter {
            it.heartbeatEnabled && it.nextHeartbeatAt.get() <= now
        }.forEach(::runHeartbeat)
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
            MessageKind.HELLO_ACK -> Unit
        }
        return true
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
            val socket = udpSocket ?: return
            broadcastAddresses().forEach { address ->
                runCatching { socket.send(DatagramPacket(bytes, bytes.size, address, config.discoveryPort)) }
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
        val endpoint = endpoints.compute(endpointKey(device.address, port)) { _, existing ->
            (existing ?: Endpoint(device, port)).apply {
                this.device = device
                this.port = port
                heartbeatEnabled = true
            }
        }!!
        val wasNew = peers.put(device.id, endpoint) == null
        publishPeers()
        if (wasNew) {
            listener.onStatus("发现 ${device.name}，正在确认连接")
            endpoint.nextHeartbeatAt.set(0)
            runHeartbeat(endpoint)
            pendingMessage?.let { encrypted ->
                ioExecutor.execute {
                    if (send(endpoint, encrypted) && pendingMessage === encrypted) pendingMessage = null
                }
            }
        }
    }

    private fun removeExpiredPeers() {
        val cutoff = System.currentTimeMillis() - PEER_TTL_MS
        val removedNames = mutableListOf<String>()
        peers.entries.forEach { entry ->
            if (entry.value.device.lastSeenAt < cutoff && peers.remove(entry.key, entry.value)) {
                removedNames += entry.value.device.name
            }
        }
        if (removedNames.isNotEmpty()) {
            publishPeers()
            listener.onStatus("与 ${removedNames.joinToString()} 心跳超时，正在后台重连")
        }
    }

    private fun registerInboundPeer(message: ClipMessage, address: String): Endpoint {
        val now = System.currentTimeMillis()
        val device = PeerDevice(message.originDeviceId, message.originDeviceName, oppositeDeviceKind(), address, now)
        val endpoint = endpoints.compute(endpointKey(address, message.originPort)) { _, existing ->
            (existing ?: Endpoint(device, message.originPort)).apply {
                this.device = device
                this.port = message.originPort
                heartbeatEnabled = true
                consecutiveFailures.set(0)
                nextHeartbeatAt.set(now + HEARTBEAT_INTERVAL_MS)
            }
        }!!
        peers.entries.removeIf { it.key != device.id && it.value.device.address == address }
        val wasNew = peers.put(device.id, endpoint) == null
        publishPeers()
        if (wasNew) listener.onStatus("已确认连接 ${device.name}")
        return endpoint
    }

    private fun touch(endpoint: Endpoint) {
        val id = endpoint.device.id
        if (id.startsWith("manual:")) return
        val now = System.currentTimeMillis()
        endpoint.device = endpoint.device.copy(lastSeenAt = now)
        if (peers[id] === endpoint) publishPeers()
    }

    private fun publishPeers() = listener.onPeersChanged(
        peers.values.distinct().map { it.device }.sortedBy { it.name.lowercase() },
    )

    private fun broadcastAddresses(): Set<InetAddress> {
        val result = linkedSetOf<InetAddress>()
        eligibleNetworkInterfaces().forEach { network ->
            network.interfaceAddresses.mapNotNullTo(result) { it.broadcast }
        }
        if (result.isEmpty()) result += InetAddress.getByName("255.255.255.255")
        return result
    }

    private fun trimSeenMessages() {
        if (seenMessageIds.size > 1_000) seenMessageIds.clear()
    }

    private fun waitForRetry(milliseconds: Long): Boolean = try {
        Thread.sleep(milliseconds)
        running.get()
    } catch (_: InterruptedException) {
        false
    }

    private fun encryptedMessage(kind: MessageKind): ByteArray = CryptoBox.encrypt(
        key,
        WireProtocol.encode(
            ClipMessage(
                id = UUID.randomUUID().toString(),
                originDeviceId = config.deviceId,
                originDeviceName = config.deviceName,
                timestamp = System.currentTimeMillis(),
                content = null,
                kind = kind,
                originPort = config.transferPort,
            ),
        ),
    )

    private fun endpointKey(address: String, port: Int) = "$address:$port"

    private fun oppositeDeviceKind(): DeviceKind = if (config.deviceKind == DeviceKind.PHONE) {
        DeviceKind.DESKTOP
    } else {
        DeviceKind.PHONE
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { udpSocket?.close() }
        runCatching { serverSocket?.close() }
        scheduler.shutdownNow()
        discoveryExecutor.shutdownNow()
        acceptExecutor.shutdownNow()
        heartbeatExecutor.shutdownNow()
        ioExecutor.shutdownNow()
        listener.onTransportState(false, "同步服务已停止")
    }

    companion object {
        private const val ANNOUNCE_INTERVAL_MS = 2_000L
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
        private const val HEARTBEAT_RETRY_MAX_MS = 60_000L
        private const val PEER_TTL_MS = 45_000L
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val TCP_RETRY_MIN_MS = 1_000L
        private const val TCP_RETRY_MAX_MS = 30_000L
        private val VIRTUAL_INTERFACE_PATTERN = Regex(
            "(?i)(tun|tap|vpn|sing|tailscale|zerotier|docker|wsl|vmware|vbox|virtualbox|hyper-v)",
        )

        private fun singleThreadExecutor(name: String) = Executors.newSingleThreadExecutor { task ->
            Thread(task, name).apply { isDaemon = true }
        }

        private fun eligibleNetworkInterfaces(): List<NetworkInterface> {
            val result = mutableListOf<NetworkInterface>()
            runCatching {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val network = interfaces.nextElement()
                    val label = "${network.name} ${network.displayName}"
                    if (network.isUp && !network.isLoopback && !network.isVirtual &&
                        !VIRTUAL_INTERFACE_PATTERN.containsMatchIn(label)
                    ) result += network
                }
            }
            return result
        }

        fun localIpv4Addresses(): List<String> = eligibleNetworkInterfaces().flatMap { network ->
            buildList {
                val items = network.inetAddresses
                while (items.hasMoreElements()) {
                    val address = items.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                        add(address.hostAddress)
                    }
                }
            }
        }.distinct()
    }
}
