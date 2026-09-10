# ClipLink

ClipLink 是一个使用 Kotlin 与 Compose 构建的局域网剪贴板同步应用。Android 手机与 Windows、macOS 或 Linux 电脑处于同一局域网，并设置相同配对码后，可以互相同步剪贴板；不依赖中心服务器。

## 当前能力

- Android 与 JVM Desktop 双端 Compose 界面
- UDP 局域网设备发现，TCP 点对点传输
- 配对码经 PBKDF2 派生 256 位密钥，消息使用 AES-256-GCM 加密与完整性校验
- 同步纯文本、HTML 富文本、图片以及文件/多文件
- 建立连接后自动补发当前剪贴板，之后每次变化自动发送；也可手动发送
- TCP 监听自动恢复、加密心跳、45 秒容错窗口与指数退避重连
- 桌面端单实例运行，避免重复启动抢占同步端口
- Android 可选前台保活，在界面关闭后继续保持设备心跳和接收
- 在线设备列表、会话内历史、点击历史重新复制
- 显示本机 IPv4；广播受限时可手动输入对方 IPv4 建立双向连接
- 设备名称、设备 ID、配对码和同步偏好持久化
- 单次内容限制为 32 MB、最多 32 个文件，传输帧有长度校验，消息 ID 去重

> 安全说明：这是 MVP 协议，尚未经过第三方安全审计。强随机默认配对码能抵抗常见猜测，但协议目前没有前向保密；不要跨不可信公共 Wi-Fi 传输密码或密钥等高敏感信息。

## 运行

环境要求：JDK 17、Android SDK（compileSdk 37）。

```powershell
# Android 调试包
.\gradlew.bat :app:assembleDebug

# 运行桌面端
.\gradlew.bat :desktop:run

# 创建当前系统的桌面安装包
.\gradlew.bat :desktop:packageDistributionForCurrentOS

# 核心协议测试
.\gradlew.bat :core:test
```

Android APK 输出在 `app/build/outputs/apk/debug/`。桌面安装包输出在 `desktop/build/compose/binaries/main/`。

## 使用方式

1. 手机和电脑接入同一个局域网（电脑可使用网线，手机可使用 Wi-Fi）。
2. 打开两端 ClipLink，在“设置”中输入完全相同的配对码并保存。
3. 两端出现在“附近设备”后，当前剪贴板会自动补发；之后复制文本、富文本、图片或文件即可同步。
4. 如果自动发现失败，在一端打开“设置 → 手动连接”，输入另一端界面显示的 IPv4 地址。建议优先在电脑端输入手机 IP；电脑会主动建立连接并定时拉取手机待发送内容，因此即使 Windows 阻止入站连接也能继续双向同步。
5. Android 10 及以上限制应用在后台读取剪贴板。手机端前台时可自动发送；在后台复制后，重新打开 ClipLink，连接建立时会自动补发当前内容，也可点击“立即同步当前剪贴板”。电脑到手机的接收不受这项读取限制影响，但应用进程仍可能被系统回收。
6. 如果手动连接也失败，请把 ClipLink 加入系统防火墙的专用网络白名单，并确认路由器没有启用 AP 隔离。

### 子网与 Windows 防火墙排查

- 地址前两段相同不代表处于同一子网。例如设备 `172.23.2.10/23` 的可直连范围是 `172.23.2.1–172.23.3.254`。
- Windows 防火墙启用 `BlockInbound` 时可能拦截 UDP 24815 和 TCP 24816。首次启动出现网络访问提示时，应只勾选“专用网络”。
- 如需手动创建规则，请用管理员 PowerShell 执行：

```powershell
New-NetFirewallRule -DisplayName "ClipLink TCP" -Direction Inbound -Protocol TCP -LocalPort 24816 -Action Allow -Profile Private -RemoteAddress LocalSubnet
New-NetFirewallRule -DisplayName "ClipLink Discovery" -Direction Inbound -Protocol UDP -LocalPort 24815 -Action Allow -Profile Private -RemoteAddress LocalSubnet
```

## 工程结构

```text
app/        Android 入口、系统剪贴板与 SharedPreferences 适配
core/       设备发现、加密传输、协议、同步状态机与单元测试
desktop/    JVM Desktop 入口、AWT 剪贴板与 Preferences 适配
shared-ui/  Android/Desktop 共用的 Compose Material 3 界面
```

## 开源项目参考

设计思路参考了以下开源项目及其公开协议，但本项目没有复制它们的实现代码：

- [KDE Connect](https://github.com/KDE/kdeconnect-kde)：设备发现、配对语义与带时间戳的剪贴板消息设计。
- [KDE Connect Protocol](https://github.com/KDE/kdeconnect-meta/blob/work/protocol-schemas/protocol.md)：小型结构化消息、能力协商与剪贴板包的边界处理。
- [LocalSend](https://github.com/localsend/localsend)：局域网优先、无中心服务器与传输加密的产品原则。
- [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform)：Android 和桌面端共享声明式 UI 的技术基础。

ClipLink 当前采用自己的轻量二进制协议，不能直接与 KDE Connect 或 LocalSend 互通。

## 内容兼容性

- 图片会以通用图片内容传输；桌面端统一写回为 PNG。
- 文件会复制到接收端本地缓存目录，再以文件列表写入系统剪贴板。文件夹、超出大小限制的内容和应用私有剪贴板格式不会同步。
- 协议 v3 与旧版只支持文本的客户端不兼容；建议手机与电脑同时升级到 1.3。

### 连接稳定性

- “设置 → 后台保持连接”仅在 Android 显示。开启后会显示低优先级常驻通知，维持局域网发现、心跳和接收；它无法绕过 Android 对后台读取本机剪贴板的系统限制。
- TCP 24816 启动失败时会自动重试，界面会显示当前监听状态。设备列表同时显示最近心跳时间。
- 自动发现默认忽略常见 VPN、TUN、Docker、WSL 和虚拟机网卡，避免向错误接口广播。
- 心跳与文件传输使用独立执行器；连续失败会按 5、10、20、40、60 秒退避重连。

## 后续路线

- 经过认证的 X25519 临时密钥协商，提供前向保密
- Android 前台服务与快捷设置磁贴
- 历史持久化、收藏与搜索
- mDNS/NSD 发现和手动 IP 连接兜底
- 自动化端到端双设备测试与正式签名发布
