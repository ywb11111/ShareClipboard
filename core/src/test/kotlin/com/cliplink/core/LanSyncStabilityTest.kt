package com.cliplink.core

import java.net.DatagramSocket
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

class LanSyncStabilityTest {
    @Test
    fun tcpListenerRetriesAfterPortBecomesAvailable() {
        val occupiedPort = ServerSocket(0)
        val transferPort = occupiedPort.localPort
        val failed = CountDownLatch(1)
        val listeningLatch = CountDownLatch(1)
        val service = LanSyncService(
            LanSyncService.Config(
                "retry-device", "重试设备", DeviceKind.DESKTOP, "TEST-CODE-1234",
                discoveryPort = DatagramSocket(0).use { it.localPort },
                transferPort = transferPort,
            ),
            object : LanSyncService.Listener {
                override fun onPeersChanged(peers: List<PeerDevice>) = Unit
                override fun onMessage(message: ClipMessage) = Unit
                override fun onStatus(message: String) = Unit
                override fun onError(message: String) = Unit
                override fun onTransportState(listening: Boolean, detail: String) {
                    if (listening) listeningLatch.countDown() else if (detail.contains("重试")) failed.countDown()
                }
            },
        )

        try {
            service.start()
            assertTrue(failed.await(3, TimeUnit.SECONDS), "端口冲突没有进入自动重试")
            occupiedPort.close()
            assertTrue(listeningLatch.await(5, TimeUnit.SECONDS), "端口释放后 TCP 服务没有恢复")
        } finally {
            runCatching { occupiedPort.close() }
            service.close()
        }
    }
}
