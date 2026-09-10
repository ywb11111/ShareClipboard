package com.cliplink.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cliplink.core.AppState
import com.cliplink.core.ClipDirection
import com.cliplink.core.ClipItem
import com.cliplink.core.ClipboardContent
import com.cliplink.core.DeviceKind
import com.cliplink.core.PeerDevice
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ClipLinkActions(
    val sendCurrent: () -> Unit,
    val copy: (ClipboardContent) -> Unit,
    val deleteHistory: (String) -> Unit,
    val clearHistory: () -> Unit,
    val setAutoSend: (Boolean) -> Unit,
    val setAutoReceive: (Boolean) -> Unit,
    val updateIdentity: (String, String) -> Boolean,
    val connectManually: (String) -> Boolean,
    val dismissError: () -> Unit,
)

private enum class Page(val title: String, val icon: ImageVector) {
    SYNC("同步", Icons.Rounded.Sync),
    HISTORY("历史", Icons.Rounded.History),
    SETTINGS("设置", Icons.Rounded.Settings),
}

@Composable
fun ClipLinkScreen(state: AppState, actions: ClipLinkActions, modifier: Modifier = Modifier) {
    var page by remember { mutableStateOf(Page.SYNC) }
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth >= 760.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                AppNavigationRail(page, { page = it }, state.peers.isNotEmpty())
                HorizontalDivider(Modifier.fillMaxHeight().width(1.dp))
                AppContent(page, state, actions, Modifier.weight(1f))
            }
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = { AppNavigationBar(page, { page = it }, state.peers.isNotEmpty()) },
            ) { padding -> AppContent(page, state, actions, Modifier.padding(padding)) }
        }
    }

    state.errorMessage?.let { error ->
        AlertDialog(
            onDismissRequest = actions.dismissError,
            confirmButton = { TextButton(onClick = actions.dismissError) { Text("知道了") } },
            title = { Text("ClipLink 提示") },
            text = { Text(error) },
        )
    }
}

@Composable
private fun AppContent(page: Page, state: AppState, actions: ClipLinkActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        TopBar(state)
        AnimatedContent(page, modifier = Modifier.fillMaxSize()) { current ->
            when (current) {
                Page.SYNC -> SyncPage(state, actions)
                Page.HISTORY -> HistoryPage(state, actions)
                Page.SETTINGS -> SettingsPage(state, actions)
            }
        }
    }
}

@Composable
private fun TopBar(state: AppState) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.ContentCopy, null, tint = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("ClipLink", fontWeight = FontWeight.Bold, fontSize = 21.sp)
            Text(
                state.statusMessage,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Surface(
            color = if (state.peers.isNotEmpty()) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(50),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape).background(
                        if (state.peers.isNotEmpty()) Color(0xFF19A66F) else MaterialTheme.colorScheme.outline,
                    ),
                )
                Spacer(Modifier.width(7.dp))
                Text(if (state.peers.isEmpty()) "等待连接" else "${state.peers.size} 台在线", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun SyncPage(state: AppState, actions: ClipLinkActions) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(28.dp),
            ) {
                BoxWithConstraints(Modifier.fillMaxWidth().padding(24.dp)) {
                    val compact = maxWidth < 560.dp
                    if (compact) {
                        Column { ConnectionSummary(state); Spacer(Modifier.height(20.dp)); PairCode(state.pairingCode) }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { ConnectionSummary(state) }
                            PairCode(state.pairingCode)
                        }
                    }
                }
            }
        }
        item {
            SectionTitle("当前剪贴板", "自动同步文本、富文本、图片和文件，单次上限 32 MB")
            Card(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        state.currentClipboard?.summary() ?: "剪贴板中还没有可同步内容",
                        color = if (state.currentClipboard == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = actions.sendCurrent,
                        enabled = state.currentClipboard != null,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(15.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, null)
                        Spacer(Modifier.width(9.dp))
                        Text("立即同步当前剪贴板")
                    }
                }
            }
        }
        item {
            SectionTitle("附近设备", "同一局域网且配对码一致时自动出现")
            if (state.peers.isEmpty()) EmptyDevices() else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.peers.forEach { DeviceRow(it) }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun ConnectionSummary(state: AppState) {
    Icon(Icons.Rounded.Wifi, null, modifier = Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(12.dp))
    Text(if (state.peers.isEmpty()) "准备连接你的设备" else "设备已安全连接", fontWeight = FontWeight.Bold, fontSize = 24.sp)
    Spacer(Modifier.height(6.dp))
    Text(
        if (state.peers.isEmpty()) "在另一台设备输入相同配对码，ClipLink 会在局域网内自动发现它。" else "剪贴板通过 AES‑GCM 加密后在局域网内直传。",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.localAddresses.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Text(
            "本机 IPv4：${state.localAddresses.joinToString()}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun PairCode(code: String) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .72f), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("配对码", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(code, fontWeight = FontWeight.ExtraBold, fontSize = 25.sp, letterSpacing = 2.sp)
        }
    }
}

@Composable
private fun EmptyDevices() {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Devices, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Text("暂未发现设备。请确认两端处于同一局域网。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DeviceRow(device: PeerDevice) {
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(
                    if (device.kind == DeviceKind.PHONE) Icons.Rounded.PhoneAndroid else Icons.Rounded.Computer,
                    null,
                    Modifier.padding(11.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name, fontWeight = FontWeight.SemiBold)
                Text(device.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.CheckCircle, "已连接", tint = Color(0xFF19A66F))
        }
    }
}

@Composable
private fun HistoryPage(state: AppState, actions: ClipLinkActions) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("剪贴板历史", "仅保存在当前运行期间，最多 50 条", Modifier.weight(1f))
            if (state.history.isNotEmpty()) TextButton(onClick = actions.clearHistory) { Text("清空") }
        }
        Spacer(Modifier.height(12.dp))
        if (state.history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.History, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(12.dp))
                    Text("还没有同步记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.history, key = { it.id }) { item -> HistoryRow(item, actions) }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

@Composable
private fun HistoryRow(item: ClipItem, actions: ClipLinkActions) {
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().clickable { actions.copy(item.content) }) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = {},
                    label = { Text(if (item.direction == ClipDirection.SENT) "已发送" else "已接收") },
                    leadingIcon = { Icon(if (item.direction == ClipDirection.SENT) Icons.AutoMirrored.Rounded.Send else Icons.Rounded.ContentCopy, null, Modifier.size(16.dp)) },
                )
                Spacer(Modifier.width(10.dp))
                Text(item.deviceName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(formatTime(item.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                IconButton(onClick = { actions.deleteHistory(item.id) }) { Icon(Icons.Rounded.DeleteOutline, "删除") }
            }
            Text(item.content.summary(), maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SettingsPage(state: AppState, actions: ClipLinkActions) {
    var name by remember(state.deviceName) { mutableStateOf(state.deviceName) }
    var code by remember(state.pairingCode) { mutableStateOf(state.pairingCode) }
    var manualTarget by remember { mutableStateOf("") }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { SectionTitle("同步偏好", "更改后立即生效") }
        item {
            SettingsCard {
                ToggleRow("自动发送", "连接后补发当前内容，之后有变化即自动发送", state.autoSend, actions.setAutoSend)
                HorizontalDivider()
                ToggleRow("自动接收", "收到内容后写入本机剪贴板", state.autoReceive, actions.setAutoReceive)
            }
        }
        item { SectionTitle("手动连接", "广播发现失败时，输入对方设备显示的 IPv4") }
        item {
            SettingsCard {
                OutlinedTextField(
                    value = manualTarget,
                    onValueChange = { manualTarget = it.trim().take(21) },
                    label = { Text("对方 IPv4 地址") },
                    placeholder = { Text("例如 172.23.2.80") },
                    supportingText = { Text("两端配对码仍需保持一致") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { actions.connectManually(manualTarget) },
                    enabled = manualTarget.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("通过 IP 连接") }
                if (state.localAddresses.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "本机：${state.localAddresses.joinToString()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { SectionTitle("设备与配对", "两端必须使用完全相同的配对码") }
        item {
            SettingsCard {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(32) },
                    label = { Text("设备名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase().filter { char -> char.isLetterOrDigit() || char == '-' }.take(24) },
                    label = { Text("配对码") },
                    supportingText = { Text("至少 8 位；修改后会重新连接") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                FilledTonalButton(onClick = { actions.updateIdentity(name, code) }, modifier = Modifier.fillMaxWidth()) {
                    Text("保存并重新连接")
                }
            }
        }
        item {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("Android 提示", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "支持文本、富文本、图片及文件（最多 32 个、合计 32 MB）。Android 10 及以上会限制后台读取剪贴板；手机端在前台时可自动同步，不在前台时请打开 ClipLink。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 4.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AppNavigationBar(selected: Page, onSelected: (Page) -> Unit, connected: Boolean) {
    NavigationBar {
        Page.entries.forEach { page ->
            NavigationBarItem(
                selected = selected == page,
                onClick = { onSelected(page) },
                icon = { NavigationIcon(page, connected) },
                label = { Text(page.title) },
            )
        }
    }
}

@Composable
private fun AppNavigationRail(selected: Page, onSelected: (Page) -> Unit, connected: Boolean) {
    NavigationRail(Modifier.width(92.dp), header = { Spacer(Modifier.height(16.dp)) }) {
        Spacer(Modifier.weight(1f))
        Page.entries.forEach { page ->
            NavigationRailItem(
                selected = selected == page,
                onClick = { onSelected(page) },
                icon = { NavigationIcon(page, connected) },
                label = { Text(page.title) },
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun NavigationIcon(page: Page, connected: Boolean) {
    Box {
        Icon(page.icon, page.title)
        if (page == Page.SYNC && connected) {
            Box(Modifier.align(Alignment.TopEnd).size(7.dp).clip(CircleShape).background(Color(0xFF19A66F)))
        }
    }
}

private val timeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatTime(timestamp: Long): String = runCatching {
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(timeFormatter)
}.getOrDefault("")
