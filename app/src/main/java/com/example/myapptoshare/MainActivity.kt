package com.example.myapptoshare

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.app.Service
import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.Binder
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.core.content.edit
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
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
    private val coordinatorState = mutableStateOf<SyncCoordinator?>(null)
    private val localNetworkDenied = mutableStateOf(false)
    private var serviceBound = false
    private val localNetworkPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        localNetworkDenied.value = !granted
        if (granted) {
            requestNotificationPermissionIfNeeded()
            connectService()
        }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            coordinatorState.value = (binder as ClipLinkService.LocalBinder).coordinator
        }

        override fun onServiceDisconnected(name: ComponentName) {
            coordinatorState.value = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ClipLinkTheme {
                val coordinator = coordinatorState.value
                if (localNetworkDenied.value) {
                    LocalNetworkPermissionRequired { requestLocalNetworkPermission() }
                } else if (coordinator == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    CoordinatorScreen(coordinator)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= 37 && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_LOCAL_NETWORK,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestLocalNetworkPermission()
        } else {
            localNetworkDenied.value = false
            requestNotificationPermissionIfNeeded()
            connectService()
        }
    }

    private fun connectService() {
        if (serviceBound) return
        val intent = Intent(this, ClipLinkService::class.java)
        val keepAlive = AndroidSettingsStore(this).get("keep_alive")?.toBooleanStrictOrNull() ?: true
        if (keepAlive) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
        serviceBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun requestLocalNetworkPermission() {
        if (Build.VERSION.SDK_INT >= 37) localNetworkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    private fun requestNotificationPermissionIfNeeded() {
        val keepAlive = AndroidSettingsStore(this).get("keep_alive")?.toBooleanStrictOrNull() ?: true
        if (!keepAlive) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStop() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        coordinatorState.value = null
        val keepAlive = AndroidSettingsStore(this).get("keep_alive")?.toBooleanStrictOrNull() ?: true
        if (!keepAlive && !isChangingConfigurations) stopService(Intent(this, ClipLinkService::class.java))
        super.onStop()
    }
}

@androidx.compose.runtime.Composable
private fun LocalNetworkPermissionRequired(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("需要局域网权限", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(10.dp))
            Text("ClipLink 需要访问本地网络，才能发现并连接你的电脑。")
            Spacer(Modifier.height(18.dp))
            Button(onClick = onRequest) { Text("授予权限") }
        }
    }
}

@androidx.compose.runtime.Composable
private fun CoordinatorScreen(coordinator: SyncCoordinator) {
    var state by remember(coordinator) { mutableStateOf(coordinator.snapshot()) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val context = LocalContext.current
    DisposableEffect(coordinator) {
        val subscription = coordinator.observe { next ->
            if (Looper.myLooper() == Looper.getMainLooper()) state = next else mainHandler.post { state = next }
        }
        onDispose { subscription.close() }
    }
    ClipLinkScreen(
        state = state,
        actions = coordinator.actions {
            val batterySettings = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            val appSettings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
            if (runCatching { context.startActivity(batterySettings) }.isFailure) {
                context.startActivity(appSettings)
            }
        },
        modifier = Modifier.safeDrawingPadding(),
    )
}

class ClipLinkService : Service() {
    inner class LocalBinder : Binder() {
        val coordinator: SyncCoordinator get() = this@ClipLinkService.coordinator
    }

    private val binder = LocalBinder()
    private lateinit var coordinator: SyncCoordinator
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate() {
        super.onCreate()
        coordinator = SyncCoordinator(
            clipboard = AndroidClipboardPort(this),
            settings = AndroidSettingsStore(this),
            defaultDeviceName = buildDeviceName(),
            deviceKind = DeviceKind.PHONE,
            supportsBackgroundMode = true,
            defaultKeepAlive = true,
            onKeepAliveChanged = ::setBackgroundMode,
        ).also { it.start() }
        if (coordinator.snapshot().keepAlive) enterForeground()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            coordinator.setKeepAlive(false)
            return START_NOT_STICKY
        }
        if (coordinator.snapshot().keepAlive) enterForeground()
        return if (coordinator.snapshot().keepAlive) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseConnectionLocks()
        coordinator.close()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!coordinator.snapshot().keepAlive) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun setBackgroundMode(enabled: Boolean) {
        if (enabled) {
            startService(Intent(this, ClipLinkService::class.java))
            enterForeground()
        } else {
            releaseConnectionLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun enterForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(NOTIFICATION_CHANNEL, "ClipLink 后台连接", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("ClipLink 正在保持连接")
            .setContentText("局域网剪贴板接收与设备心跳正在运行")
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                0,
                "停止后台连接",
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, ClipLinkService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        acquireConnectionLocks()
    }

    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun acquireConnectionLocks() {
        if (wakeLock?.isHeld != true) {
            wakeLock = runCatching {
                getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClipLink:Connection")
                    .apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
        }
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        if (wifiLock?.isHeld != true) {
            wifiLock = runCatching {
                wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ClipLink:Wifi")
                    .apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
        }
        if (multicastLock?.isHeld != true) {
            multicastLock = runCatching {
                wifi.createMulticastLock("ClipLink:Discovery")
                    .apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
        }
    }

    private fun releaseConnectionLocks() {
        runCatching { if (multicastLock?.isHeld == true) multicastLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        multicastLock = null
        wifiLock = null
        wakeLock = null
    }

    companion object {
        private const val NOTIFICATION_CHANNEL = "cliplink_connection"
        private const val NOTIFICATION_ID = 24816
        private const val ACTION_STOP = "com.cliplink.action.STOP_BACKGROUND"
    }
}

private fun buildDeviceName(): String {
    val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
    return "$maker ${Build.MODEL}".take(32)
}

private fun SyncCoordinator.actions(openBatterySettings: () -> Unit) = ClipLinkActions(
    sendCurrent = ::sendCurrentClipboard,
    copy = ::copyToClipboard,
    deleteHistory = ::deleteHistory,
    clearHistory = ::clearHistory,
    setAutoSend = ::setAutoSend,
    setAutoReceive = ::setAutoReceive,
    setKeepAlive = ::setKeepAlive,
    openBatterySettings = openBatterySettings,
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
