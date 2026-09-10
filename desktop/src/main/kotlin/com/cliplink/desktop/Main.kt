package com.cliplink.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.cliplink.core.ClipboardPort
import com.cliplink.core.DeviceKind
import com.cliplink.core.SettingsStore
import com.cliplink.core.SyncCoordinator
import com.cliplink.ui.ClipLinkActions
import com.cliplink.ui.ClipLinkScreen
import com.cliplink.ui.ClipLinkTheme
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import javax.swing.SwingUtilities

fun main() = application {
    val coordinator = remember {
        SyncCoordinator(
            clipboard = DesktopClipboardPort(),
            settings = DesktopSettingsStore(),
            defaultDeviceName = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("我的电脑").take(32),
            deviceKind = DeviceKind.DESKTOP,
        ).also { it.start() }
    }
    var state by remember { mutableStateOf(coordinator.snapshot()) }

    DisposableEffect(coordinator) {
        val subscription = coordinator.observe { next ->
            if (SwingUtilities.isEventDispatchThread()) state = next
            else SwingUtilities.invokeLater { state = next }
        }
        onDispose {
            subscription.close()
            coordinator.close()
        }
    }

    Window(
        onCloseRequest = {
            coordinator.close()
            exitApplication()
        },
        title = "ClipLink",
        resizable = true,
    ) {
        window.minimumSize = java.awt.Dimension(520, 620)
        ClipLinkTheme { ClipLinkScreen(state, coordinator.actions()) }
    }
}

private fun SyncCoordinator.actions() = ClipLinkActions(
    sendCurrent = ::sendCurrentClipboard,
    copy = ::copyToClipboard,
    deleteHistory = ::deleteHistory,
    clearHistory = ::clearHistory,
    setAutoSend = ::setAutoSend,
    setAutoReceive = ::setAutoReceive,
    updateIdentity = ::updateIdentity,
    connectManually = ::connectManually,
    dismissError = ::dismissError,
)

private class DesktopClipboardPort : ClipboardPort {
    private val systemClipboard = Toolkit.getDefaultToolkit().systemClipboard
    private var scheduler: ScheduledExecutorService? = null
    @Volatile private var lastText: String? = null

    override fun readText(): String? = runCatching {
        if (systemClipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
            systemClipboard.getData(DataFlavor.stringFlavor) as? String
        } else null
    }.getOrNull()

    override fun writeText(text: String) {
        lastText = text
        repeat(3) { attempt ->
            if (runCatching { systemClipboard.setContents(StringSelection(text), null) }.isSuccess) return
            if (attempt < 2) Thread.sleep(40)
        }
    }

    override fun startWatching(onChanged: (String) -> Unit) {
        lastText = readText()
        scheduler = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "cliplink-clipboard").apply { isDaemon = true }
        }.also { executor ->
            executor.scheduleWithFixedDelay({
                val text = readText()
                if (!text.isNullOrBlank() && text != lastText) {
                    lastText = text
                    onChanged(text)
                }
            }, 300, 500, TimeUnit.MILLISECONDS)
        }
    }

    override fun stopWatching() {
        scheduler?.shutdownNow()
        scheduler = null
    }
}

private class DesktopSettingsStore : SettingsStore {
    private val preferences = Preferences.userRoot().node("com/cliplink")
    override fun get(key: String): String? = preferences.get(key, null)
    override fun put(key: String, value: String) = preferences.put(key, value)
}
