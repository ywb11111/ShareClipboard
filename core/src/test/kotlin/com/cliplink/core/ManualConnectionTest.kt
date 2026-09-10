package com.cliplink.core

import java.net.DatagramSocket
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManualConnectionTest {
    @Test
    fun manualConnectionHandshakesAndTransfersEncryptedClipboard() {
        val portA = freeTcpPort()
        val portB = freeTcpPort().let { if (it == portA) freeTcpPort() else it }
        val discoveryA = freeUdpPort()
        val discoveryB = freeUdpPort().let { if (it == discoveryA) freeUdpPort() else it }
        val peersA = CountDownLatch(1)
        val peersB = CountDownLatch(1)
        val received = CountDownLatch(1)
        val peerCountA = AtomicInteger(0)
        val disconnectedAfterConnect = AtomicBoolean(false)
        val sentContent = ClipboardContent(
            ClipboardKind.FILES,
            files = listOf(ClipboardFile("sample.bin", "application/octet-stream", ByteArray(16_384) { (it % 251).toByte() })),
        )
        var receivedFingerprint = ""

        val serviceA = LanSyncService(
            LanSyncService.Config("desktop-a", "电脑", DeviceKind.DESKTOP, "TEST-CODE-1234", discoveryA, portA),
            object : LanSyncService.Listener {
                override fun onPeersChanged(peers: List<PeerDevice>) {
                    if (peers.isNotEmpty()) peersA.countDown()
                    if (peerCountA.getAndSet(peers.size) > 0 && peers.isEmpty()) disconnectedAfterConnect.set(true)
                }
                override fun onMessage(message: ClipMessage) = Unit
                override fun onStatus(message: String) = Unit
                override fun onError(message: String) = Unit
            },
        )
        val serviceB = LanSyncService(
            LanSyncService.Config("phone-b", "手机", DeviceKind.PHONE, "TEST-CODE-1234", discoveryB, portB),
            object : LanSyncService.Listener {
                override fun onPeersChanged(peers: List<PeerDevice>) { if (peers.isNotEmpty()) peersB.countDown() }
                override fun onMessage(message: ClipMessage) {
                    receivedFingerprint = message.content?.fingerprint().orEmpty()
                    received.countDown()
                }
                override fun onStatus(message: String) = Unit
                override fun onError(message: String) = Unit
            },
        )

        try {
            serviceA.start()
            serviceB.start()
            assertTrue(serviceA.addManualPeer("127.0.0.1:$portB"))
            assertTrue(peersA.await(5, TimeUnit.SECONDS), "发起端未收到握手确认")
            assertTrue(peersB.await(5, TimeUnit.SECONDS), "接收端未登记手动设备")

            assertEquals(1, serviceA.broadcast(sentContent))
            assertTrue(received.await(5, TimeUnit.SECONDS), "加密剪贴板消息未送达")
            assertEquals(sentContent.fingerprint(), receivedFingerprint)
            Thread.sleep(12_000)
            assertEquals(1, peerCountA.get(), "心跳期间设备意外离线")
            assertEquals(false, disconnectedAfterConnect.get(), "稳定连接被误判为断开")
        } finally {
            serviceA.close()
            serviceB.close()
        }
    }

    private fun freeTcpPort(): Int = ServerSocket(0).use { it.localPort }
    private fun freeUdpPort(): Int = DatagramSocket(0).use { it.localPort }
}
