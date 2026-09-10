package com.example.myapptoshare

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.cliplink.core.ClipboardPort
import com.cliplink.core.DeviceKind
import com.cliplink.core.SettingsStore
import com.cliplink.core.SyncCoordinator
import com.cliplink.ui.ClipLinkActions
import com.cliplink.ui.ClipLinkScreen
import com.cliplink.ui.ClipLinkTheme

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
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var callback: ((String) -> Unit)? = null
    private var lastText: String? = null
    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        val text = readText()
        if (!text.isNullOrBlank() && text != lastText) {
            lastText = text
            callback?.invoke(text)
        }
    }

    override fun readText(): String? = runCatching {
        clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()
    }.getOrNull()

    override fun writeText(text: String) {
        lastText = text
        val write = { clipboard.setPrimaryClip(ClipData.newPlainText("ClipLink", text)) }
        if (Looper.myLooper() == Looper.getMainLooper()) write() else mainHandler.post { write() }
    }

    override fun startWatching(onChanged: (String) -> Unit) {
        callback = onChanged
        lastText = readText()
        clipboard.addPrimaryClipChangedListener(listener)
    }

    override fun stopWatching() {
        clipboard.removePrimaryClipChangedListener(listener)
        callback = null
    }
}

private class AndroidSettingsStore(context: Context) : SettingsStore {
    private val preferences = context.getSharedPreferences("cliplink", Context.MODE_PRIVATE)
    override fun get(key: String): String? = preferences.getString(key, null)
    override fun put(key: String, value: String) {
        preferences.edit { putString(key, value) }
    }
}
