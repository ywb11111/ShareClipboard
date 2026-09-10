package com.example.myapptoshare

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.edit
import androidx.core.content.FileProvider
import android.provider.OpenableColumns
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var coordinator: SyncCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        coordinator = SyncCoordinator(
            clipboard = AndroidClipboardPort(this),
            settings = AndroidSettingsStore(this),
            defaultDeviceName = buildDeviceName(),
            deviceKind = DeviceKind.PHONE,
        ).also { it.start() }

        setContent {
            var state by remember { mutableStateOf(coordinator.snapshot()) }
            DisposableEffect(coordinator) {
                val subscription = coordinator.observe { next -> runOnUiThread { state = next } }
                onDispose { subscription.close() }
            }

            ClipLinkTheme {
                ClipLinkScreen(
                    state = state,
                    actions = coordinator.actions(),
                    modifier = Modifier.safeDrawingPadding(),
                )
            }
        }
    }

    override fun onDestroy() {
        coordinator.close()
        super.onDestroy()
    }

    private fun buildDeviceName(): String {
        val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        return "$maker ${Build.MODEL}".take(32)
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

private class AndroidClipboardPort(context: Context) : ClipboardPort {
    private val context = context.applicationContext
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val resolver = context.contentResolver
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reader = Executors.newSingleThreadExecutor { task -> Thread(task, "cliplink-clipboard").apply { isDaemon = true } }
    private var callback: ((ClipboardContent) -> Unit)? = null
    @Volatile private var lastFingerprint: String? = null
    @Volatile private var suppressNextChange = false
    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        reader.execute {
            val content = readContent()
            val fingerprint = content?.fingerprint()
            if (content != null && !content.isEmpty() && suppressNextChange) {
                suppressNextChange = false
                lastFingerprint = fingerprint
            } else if (content != null && !content.isEmpty() && fingerprint != lastFingerprint) {
                lastFingerprint = fingerprint
                callback?.invoke(content)
            }
        }
    }

    override fun readContent(): ClipboardContent? = runCatching {
        val clip = clipboard.primaryClip?.takeIf { it.itemCount > 0 } ?: return null
        val uriItems = (0 until clip.itemCount).mapNotNull { index -> clip.getItemAt(index).uri }
        if (uriItems.isNotEmpty()) {
            var remaining = com.cliplink.core.WireProtocol.MAX_CONTENT_BYTES
            val files = mutableListOf<ClipboardFile>()
            uriItems.take(com.cliplink.core.WireProtocol.MAX_FILES).forEach { uri ->
                val file = readUri(uri, remaining) ?: return null
                files += file
                remaining -= file.bytes.size
            }
            if (files.size == 1 && files[0].mimeType.startsWith("image/")) {
                return ClipboardContent(
                    kind = ClipboardKind.IMAGE,
                    mimeType = files[0].mimeType,
                    fileName = files[0].name,
                    bytes = files[0].bytes,
                )
            }
            return ClipboardContent(ClipboardKind.FILES, files = files)
        }
        val item = clip.getItemAt(0)
        val html = item.htmlText?.toString().orEmpty()
        val text = item.text?.toString().orEmpty()
        when {
            html.isNotEmpty() -> ClipboardContent(ClipboardKind.HTML, text = text, html = html, mimeType = "text/html")
            text.isNotEmpty() -> ClipboardContent.plainText(text)
            else -> null
        }
    }.getOrNull()

    override fun writeContent(content: ClipboardContent) {
        lastFingerprint = content.fingerprint()
        suppressNextChange = true
        val clip = when (content.kind) {
            ClipboardKind.TEXT -> ClipData.newPlainText("ClipLink", content.text)
            ClipboardKind.HTML -> ClipData.newHtmlText("ClipLink", content.text, content.html)
            ClipboardKind.IMAGE -> {
                val name = safeName(content.fileName.ifBlank { "cliplink-image.png" })
                val directory = newReceivedDirectory()
                ClipData.newUri(resolver, name, saveReceived(directory, name, content.bytes))
            }
            ClipboardKind.FILES -> {
                val directory = newReceivedDirectory()
                val uris = content.files.map { item -> saveReceived(directory, availableName(directory, safeName(item.name)), item.bytes) }
                if (uris.isEmpty()) return
                ClipData.newUri(resolver, content.files.first().name, uris.first()).also { data ->
                    uris.drop(1).forEach { data.addItem(ClipData.Item(it)) }
                }
            }
        }
        val write = { clipboard.setPrimaryClip(clip) }
        if (Looper.myLooper() == Looper.getMainLooper()) write() else mainHandler.post { write() }
    }

    override fun startWatching(onChanged: (ClipboardContent) -> Unit) {
        callback = onChanged
        lastFingerprint = readContent()?.fingerprint()
        clipboard.addPrimaryClipChangedListener(listener)
    }

    override fun stopWatching() {
        clipboard.removePrimaryClipChangedListener(listener)
        callback = null
        reader.shutdownNow()
    }

    private fun readUri(uri: Uri, maxBytes: Int): ClipboardFile? = runCatching {
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        var name = "clipboard-item"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
        }
        val bytes = resolver.openInputStream(uri)?.use { readLimited(it, maxBytes) } ?: return null
        ClipboardFile(safeName(name), mime, bytes)
    }.getOrNull()

    private fun readLimited(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= maxBytes) { "剪贴板文件超过 32 MB" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun newReceivedDirectory() = File(
        context.cacheDir,
        "cliplink_received/${System.currentTimeMillis()}-${System.nanoTime()}",
    ).apply { mkdirs() }

    private fun saveReceived(directory: File, name: String, bytes: ByteArray): Uri {
        val file = File(directory, name)
        file.writeBytes(bytes)
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    private fun availableName(directory: File, requested: String): String {
        if (!File(directory, requested).exists()) return requested
        val dot = requested.lastIndexOf('.').takeIf { it > 0 } ?: requested.length
        val base = requested.substring(0, dot)
        val extension = requested.substring(dot)
        var number = 2
        while (File(directory, "$base ($number)$extension").exists()) number++
        return "$base ($number)$extension"
    }

    private fun safeName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120).ifBlank { "clipboard-item" }
}

private class AndroidSettingsStore(context: Context) : SettingsStore {
    private val preferences = context.getSharedPreferences("cliplink", Context.MODE_PRIVATE)
    override fun get(key: String): String? = preferences.getString(key, null)
    override fun put(key: String, value: String) {
        preferences.edit { putString(key, value) }
    }
}
