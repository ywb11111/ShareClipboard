package com.cliplink.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.cliplink.core.ClipboardPort
import com.cliplink.core.ClipboardContent
import com.cliplink.core.ClipboardFile
import com.cliplink.core.ClipboardKind
import com.cliplink.core.DeviceKind
import com.cliplink.core.SettingsStore
import com.cliplink.core.SyncCoordinator
import com.cliplink.ui.ClipLinkActions
import com.cliplink.ui.ClipLinkScreen
import com.cliplink.ui.ClipLinkTheme
import java.awt.Toolkit
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import javax.swing.SwingUtilities
import javax.imageio.ImageIO

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
    @Volatile private var lastFingerprint: String? = null
    @Volatile private var suppressNextChange = false

    override fun readContent(): ClipboardContent? = runCatching {
        when {
            systemClipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor) -> readFiles()
            systemClipboard.isDataFlavorAvailable(DataFlavor.imageFlavor) -> readImage()
            systemClipboard.isDataFlavorAvailable(DataFlavor.fragmentHtmlFlavor) -> {
                val html = systemClipboard.getData(DataFlavor.fragmentHtmlFlavor) as? String ?: return null
                val plain = if (systemClipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                    systemClipboard.getData(DataFlavor.stringFlavor) as? String ?: ""
                } else ""
                ClipboardContent(ClipboardKind.HTML, text = plain, html = html, mimeType = "text/html")
            }
            systemClipboard.isDataFlavorAvailable(DataFlavor.stringFlavor) -> {
                (systemClipboard.getData(DataFlavor.stringFlavor) as? String)?.takeIf { it.isNotEmpty() }?.let(ClipboardContent::plainText)
            }
            else -> null
        }
    }.getOrNull()

    override fun writeContent(content: ClipboardContent) {
        val transferable: Transferable = when (content.kind) {
            ClipboardKind.TEXT -> StringSelection(content.text)
            ClipboardKind.HTML -> HtmlSelection(content.text, content.html)
            ClipboardKind.IMAGE -> ImageSelection(ImageIO.read(ByteArrayInputStream(content.bytes)) ?: return)
            ClipboardKind.FILES -> FileListSelection(saveReceivedFiles(content.files))
        }
        lastFingerprint = content.fingerprint()
        suppressNextChange = true
        repeat(3) { attempt ->
            if (runCatching { systemClipboard.setContents(transferable, null) }.isSuccess) return
            if (attempt < 2) Thread.sleep(40)
        }
        suppressNextChange = false
    }

    override fun startWatching(onChanged: (ClipboardContent) -> Unit) {
        lastFingerprint = readContent()?.fingerprint()
        scheduler = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "cliplink-clipboard").apply { isDaemon = true }
        }.also { executor ->
            executor.scheduleWithFixedDelay({
                val content = readContent()
                val fingerprint = content?.fingerprint()
                if (content != null && !content.isEmpty() && suppressNextChange) {
                    suppressNextChange = false
                    lastFingerprint = fingerprint
                } else if (content != null && !content.isEmpty() && fingerprint != lastFingerprint) {
                    lastFingerprint = fingerprint
                    onChanged(content)
                }
            }, 300, 500, TimeUnit.MILLISECONDS)
        }
    }

    override fun stopWatching() {
        scheduler?.shutdownNow()
        scheduler = null
    }

    @Suppress("UNCHECKED_CAST")
    private fun readFiles(): ClipboardContent? {
        val source = systemClipboard.getData(DataFlavor.javaFileListFlavor) as? List<File> ?: return null
        val regularFiles = source.filter { it.isFile }.take(com.cliplink.core.WireProtocol.MAX_FILES)
        if (regularFiles.isEmpty()) return null
        var total = 0L
        val files = regularFiles.map { file ->
            total += file.length()
            require(total <= com.cliplink.core.WireProtocol.MAX_CONTENT_BYTES) { "剪贴板文件超过 32 MB" }
            ClipboardFile(file.name, Files.probeContentType(file.toPath()) ?: "application/octet-stream", file.readBytes())
        }
        return ClipboardContent(ClipboardKind.FILES, files = files)
    }

    private fun readImage(): ClipboardContent? {
        val image = systemClipboard.getData(DataFlavor.imageFlavor) as? Image ?: return null
        val width = image.getWidth(null)
        val height = image.getHeight(null)
        if (width <= 0 || height <= 0) return null
        val rendered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = rendered.createGraphics()
        try {
            graphics.drawImage(image, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        val output = ByteArrayOutputStream()
        ImageIO.write(rendered, "png", output)
        require(output.size() <= com.cliplink.core.WireProtocol.MAX_CONTENT_BYTES) { "剪贴板图片超过 32 MB" }
        return ClipboardContent(ClipboardKind.IMAGE, mimeType = "image/png", fileName = "clipboard.png", bytes = output.toByteArray())
    }

    private fun saveReceivedFiles(files: List<ClipboardFile>): List<File> {
        val root = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "ClipLink/received/${System.currentTimeMillis()}")
        root.mkdirs()
        return files.map { item -> File(root, availableName(root, safeName(item.name))).apply { writeBytes(item.bytes) } }
    }

    private fun safeName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120).ifBlank { "clipboard-item" }

    private fun availableName(directory: File, requested: String): String {
        if (!File(directory, requested).exists()) return requested
        val dot = requested.lastIndexOf('.').takeIf { it > 0 } ?: requested.length
        val base = requested.substring(0, dot)
        val extension = requested.substring(dot)
        var number = 2
        while (File(directory, "$base ($number)$extension").exists()) number++
        return "$base ($number)$extension"
    }
}

private class ImageSelection(private val image: Image) : Transferable {
    override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
    override fun getTransferData(flavor: DataFlavor): Any {
        if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
        return image
    }
}

private class FileListSelection(private val files: List<File>) : Transferable {
    override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
    override fun getTransferData(flavor: DataFlavor): Any {
        if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
        return files
    }
}

private class HtmlSelection(private val plain: String, private val html: String) : Transferable {
    private val flavors = arrayOf(DataFlavor.fragmentHtmlFlavor, DataFlavor.stringFlavor)
    override fun getTransferDataFlavors() = flavors
    override fun isDataFlavorSupported(flavor: DataFlavor) = flavors.contains(flavor)
    override fun getTransferData(flavor: DataFlavor): Any = when (flavor) {
        DataFlavor.fragmentHtmlFlavor -> html
        DataFlavor.stringFlavor -> plain
        else -> throw UnsupportedFlavorException(flavor)
    }
}

private class DesktopSettingsStore : SettingsStore {
    private val preferences = Preferences.userRoot().node("com/cliplink")
    override fun get(key: String): String? = preferences.get(key, null)
    override fun put(key: String, value: String) = preferences.put(key, value)
}
