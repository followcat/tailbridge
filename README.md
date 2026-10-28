# TailBridge

**Keep wireless ADB reachable from anywhere** — an Android helper app that supervises a tiny reverse-ADB connector, parking a persistent outbound connection on your relay host so `adb connect` works through NAT/CGNAT without a second VPN.

Designed to coexist with a single-VPN setup (e.g. FlClash/mihomo with an embedded `type: tailscale` outbound): TailBridge doesn't own the VPN slot, needs no Termux, no root.

---

```
 ┌──────────┐  adb connect host:2223   ┌─────────┐   parked conn   ┌──────────────┐
 │ desktop  │ ───────────────────────▶ │ relay   │ ◀────────────── │ adbstitch    │
 │   adb    │                          │ :2223   │   (outbound,    │ (on phone,   │
 └──────────┘                          │ :2222   │    kept alive)  │  supervised) │
                                      └────┬────┘                 └──────┬───────┘
                                           │ splice the two TCP conns    │
                                           └───────────▶ 127.0.0.1:adbd ◀┘
```

- Phone → `host:2222`: outbound "park" connection held open by `adbstitch` (works on any network the phone can reach, including through a Tailscale-in-mihomo outbound).
- Desktop → `host:2223`: the relay splices your `adb` connection onto the parked one; the phone side then lazily dials `127.0.0.1:<wireless-debugging port>` at handshake time.

## Screenshots

<p>
  <img src="docs/screenshot-stopped.png" width="240" alt="stopped">
  <img src="docs/screenshot-main.png" width="240" alt="connecting">
  <img src="docs/screenshot-cards.png" width="240" alt="cards">
</p>

## Features

- **Foreground-service watchdog** — respawns the connector within ~1.5 s if it dies; optional boot autostart
- **mDNS auto-discovery** — tracks the wireless-debugging port (`_adb-tls-connect._tcp`) so port changes never need manual edits
- **Live status** — parked / bridged / reconnecting state, adbd online probe, WLAN/Tailnet addresses, one-tap copy of the desktop `adb connect` command
- **Self-healing transport** — the connector re-parks on a 90 s idle timeout so dead-without-FIN zombie connections recover on their own
- **Polished Material3 UI** — dark theme, state-driven colors, haptics

## Repository layout

```
app/        Android app (Kotlin + Jetpack Compose, Material3)
native/     adbstitch.c — ~130-line static C connector (poll-based TCP splice)
bridge/     adb_bridge2.py — asyncio splice relay for the server side
docs/       screenshots
```

## Build

```bash
# 1. Build the connector binary (needs Android NDK) — produces a fake .so
native/build.sh           # auto-detects $ANDROID_HOME/ndk; or set NDK_HOME

# 2. Build the APK
gradle :app:assembleDebug # or ./gradlew if you generate a wrapper
```

## Deploy & use

1. On a host the phone can reach (a VPS, e.g. your Tailnet node), run:

   ```bash
   python3 bridge/adb_bridge2.py        # listens on 2222 (park) + 2223 (adb)
   ```

   Bind it to your tailnet IP if you only want tailnet access.

2. Install the APK, open **TailBridge**, set `fvps 地址` / `停驻口`, tap **拉起反向桥**.

3. From the desktop:

   ```bash
   adb connect <host>:2223
   adb -s <host>:2223 shell getprop ro.product.model
   adb -s <host>:2223 forward tcp:9222 localabstract:chrome_devtools_remote
   ```

Wireless debugging must be enabled on the phone (Developer options → Wireless debugging). ADB's normal TLS + pairing authorization still applies — the bridge doesn't weaken authentication.

## Limitations

- **Wi-Fi off = no adbd.** Android kills the wireless-debugging listener when Wi-Fi is disabled — adb-over-cellular is a platform limitation, not something this tool can bypass. The parked channel survives, but there's no adbd to reach.
- adbd has a ~1–3 min cooldown after transport loss (LAN included) — retries are normal.
- One concurrent adb session per parked connection (by design).

---

# TailBridge（中文）

让**无线 ADB 突破网络限制**——Android 小助手 App，守护一个极小的反向连接器：手机主动把一条出站连接"停驻"到你的中继机上，桌面 `adb connect` 经由中继拼接直达手机。适配单 VPN 槽架构（如 FlClash/mihomo 内嵌 `type: tailscale` 出站）——不占用 VPN 槽、不需要 Termux、不需要 root。

## 特性

- 前台服务看门狗，进程死亡 ~1.5s 内重拉；可选开机自启
- mDNS 自动跟踪无线调试端口，端口变化自动适配
- 实时状态：停驻/桥接/重连 + adbd 在线探测 + 一键复制 adb 命令
- 90s 停驻超时自愈，僵尸连接自动重建
- Material3 深色 UI，状态驱动配色 + 触感反馈

## 使用

1. 中继机跑 `python3 bridge/adb_bridge2.py`（2222 停驻口 / 2223 adb 口，建议绑定 tailnet IP）
2. 手机装 APK → 填地址 → 点"拉起反向桥"
3. 桌面 `adb connect <主机>:2223` 即可

要求开发者选项里打开"无线调试"；adb 本身的 TLS+配对认证不变。

## 已知边界

- **关 WiFi → adbd 监听消失**（无线调试绑定 WiFi，平台限制）→ 纯蜂窝下无法 adb
- transport 断开后 adbd 有 1–3 分钟冷却窗，重试即可

## License

MIT
