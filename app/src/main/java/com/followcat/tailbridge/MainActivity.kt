package com.followcat.tailbridge

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

private val Bg = Color(0xFF06090E)
private val Card = Color(0xFF0E131B)
private val CardHi = Color(0xFF151D29)
private val Stroke = Color(0xFF1F2937)
private val Accent = Color(0xFF2DD4BF)
private val Good = Color(0xFF34D399)
private val Warn = Color(0xFFFBBF24)
private val Bad = Color(0xFFF87171)
private val Dim = Color(0xFF7C8698)
private val Txt = Color(0xFFE9EDF5)
private val Mono = FontFamily.Monospace

private fun stateColor(st: BridgeState, alive: Boolean): Color = when {
    st == BridgeState.BRIDGED -> Good
    st == BridgeState.PARKED -> Accent
    st == BridgeState.CONNECTING -> Warn
    alive -> Accent
    else -> Dim
}

class MainActivity : ComponentActivity() {

    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}
    private val nearbyPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            nearbyPerm.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        setContent { AppUI() }
    }

    @Composable
    fun AppUI() {
        val st by Bridge.state.collectAsState()
        val alive by Bridge.childAlive.collectAsState()
        val logs by Bridge.log.collectAsState()
        val adbdPortF by Bridge.adbdPortFlow.collectAsState()
        val adbdAlive by Bridge.adbdAlive.collectAsState()
        val adbdSource by Bridge.adbdPortSource.collectAsState()
        val adbdProbing by Bridge.adbdProbing.collectAsState()
        val manualPort by Bridge.manualPort.collectAsState()
        var host by remember { mutableStateOf(Bridge.host(this)) }
        var parkPort by remember { mutableStateOf(Bridge.parkPort(this).toString()) }
        val adbdPort = (adbdPortF.takeIf { it > 0 } ?: Bridge.adbdPort(this)).toString()
        var auto by remember { mutableStateOf(Bridge.autostart(this)) }
        val haptic = LocalHapticFeedback.current

        MaterialTheme(colorScheme = darkColorScheme(
            background = Bg, surface = Card, primary = Accent,
            onBackground = Txt, onSurface = Txt
        )) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF0A1017), Bg)
                        )
                    )
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Header(st, alive)
                StatusCard(st, alive)
                ControlCard(st, host, parkPort, adbdPort, manualPort,
                    onHost = { host = it }, onPark = { parkPort = it },
                    onManual = {
                        Bridge.prefs(this@MainActivity).edit()
                            .putBoolean(Bridge.K_MANUAL, it).apply()
                        Bridge.manualPort.value = it
                        if (it) Bridge.adbdPortSource.value = "manual"
                    },
                    onSave = {
                        Bridge.prefs(this@MainActivity).edit()
                            .putString(Bridge.K_HOST, host)
                            .putInt(Bridge.K_PARK, parkPort.toIntOrNull() ?: 2222)
                            .apply()
                    },
                    onToggle = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (st != BridgeState.STOPPED) BridgeService.stop(this@MainActivity)
                        else BridgeService.start(this@MainActivity)
                    })
                NetCard(adbdPort, adbdAlive, adbdSource, adbdProbing)
                PrefCard(auto, onAuto = {
                    auto = it
                    Bridge.prefs(this@MainActivity).edit().putBoolean(Bridge.K_AUTO, it).apply()
                })
                LogCard(logs)
                Text(
                    "v1.1 · 桥接保活由前台服务托管",
                    color = Dim.copy(alpha = 0.55f), fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    @Composable
    fun Header(st: BridgeState, alive: Boolean) {
        val c = stateColor(st, alive)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Accent.copy(alpha = 0.9f), Color(0xFF0E7490))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Send, null, tint = Color(0xFF04211C))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("TailBridge", fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    color = Txt)
                Text("ADB over Tailscale · 反向桥", color = Dim, fontSize = 12.sp)
            }
            val chipBg by animateColorAsState(c.copy(alpha = 0.14f), label = "chip")
            val chipFg by animateColorAsState(c, label = "chipfg")
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(chipBg)
                    .border(1.dp, chipFg.copy(alpha = 0.35f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(chipFg))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (alive) "守护中" else "未运行",
                    color = chipFg, fontSize = 11.sp, fontWeight = FontWeight.Medium
                )
            }
        }
    }

    @Composable
    fun StatusCard(st: BridgeState, alive: Boolean) {
        val c = stateColor(st, alive)
        val bgTop by animateColorAsState(c.copy(alpha = 0.16f), label = "st")
        val label = when {
            st == BridgeState.BRIDGED -> "ADB 会话桥接中"
            st == BridgeState.PARKED -> "已停驻 · 等待桌面"
            st == BridgeState.CONNECTING -> "连接中…"
            alive -> "运行中"
            else -> "已停止"
        }
        val sub = when (st) {
            BridgeState.BRIDGED -> "桌面 adb 会话已通过 tailnet 桥接"
            BridgeState.PARKED -> "停驻通道保持中，随时可 adb connect"
            BridgeState.CONNECTING -> "正在经 FlClash → tailnet 拨出"
            BridgeState.STOPPED -> "点击下方按钮拉起反向桥"
        }
        val pulse = rememberInfiniteTransition(label = "p")
        val glowA by pulse.animateFloat(0f, 1f,
            infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing)),
            label = "g")
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(bgTop, Card)))
                .border(1.dp, c.copy(alpha = 0.22f), RoundedCornerShape(24.dp))
                .padding(22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 脉动光环
                Box(contentAlignment = Alignment.Center) {
                    if (st == BridgeState.CONNECTING || st == BridgeState.BRIDGED) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .drawBehind {
                                    drawCircle(
                                        c.copy(alpha = (1f - glowA) * 0.35f),
                                        radius = size.minDimension / 2 * (1f + glowA * 0.6f)
                                    )
                                }
                        )
                    }
                    Box(
                        Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(c)
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    AnimatedContent(
                        targetState = label, label = "st-l",
                        transitionSpec = {
                            fadeIn(tween(200)) togetherWith fadeOut(tween(150))
                        }
                    ) { Text(it, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Txt) }
                    Spacer(Modifier.height(4.dp))
                    Text(sub, color = Dim, fontSize = 12.5.sp)
                }
            }
        }
    }

    @Composable
    fun ControlCard(
        st: BridgeState, host: String, parkPort: String, adbdPort: String,
        manual: Boolean,
        onHost: (String) -> Unit, onPark: (String) -> Unit,
        onManual: (Boolean) -> Unit,
        onSave: () -> Unit, onToggle: () -> Unit
    ) {
        val running = st != BridgeState.STOPPED
        Card(colors = CardDefaults.cardColors(containerColor = Card),
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Stroke)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle(Icons.Filled.Settings, "桥接端点")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field("fvps 地址", host, Modifier.weight(1.4f), onChange = onHost)
                    Field("停驻口", parkPort, Modifier.weight(1f), num = true, onChange = onPark)
                }
                Field(
                    if (manual) "本机 adbd 端口（手动锁定 · 点锁恢复自动）"
                              else "本机 adbd 端口（自动侦查 · 手填即锁定）",
                    adbdPort, Modifier.fillMaxWidth(), num = true,
                    onChange = { if (!manual) onManual(true) },
                    trailing = {
                        IconButton(onClick = { onManual(!manual) }) {
                            Icon(
                                if (manual) Icons.Filled.Lock else Icons.Filled.Refresh,
                                if (manual) "手动锁定中" else "自动侦查中",
                                tint = if (manual) Warn else Dim,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    })
                Text(
                    if (manual)
                        "手动锁定: 端口固定为你填的值(配对转发/tcpip 5555 用); 点右侧锁回自动侦查"
                    else
                        "端口自动侦查: WiFi 下走 mDNS, 无 WiFi 时扫 loopback(TLS+CNXN); 手填端口会锁定; 停驻口 = 手机外拨到 fvps, 桌面 adb connect 用 2223",
                    color = Dim, fontSize = 11.sp, lineHeight = 15.sp)

                val btnBg = if (running)
                    Brush.horizontalGradient(listOf(Color(0xFF4C1D24), Color(0xFF3B161C)))
                else
                    Brush.horizontalGradient(listOf(Color(0xFF0F766E), Color(0xFF155E4E)))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(btnBg)
                        .border(
                            1.dp,
                            if (running) Bad.copy(alpha = 0.4f) else Accent.copy(alpha = 0.4f),
                            RoundedCornerShape(16.dp)
                        )
                        .clickable { onSave(); onToggle() },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (running) Icons.Filled.Close else Icons.Filled.PlayArrow,
                            null,
                            tint = if (running) Bad else Color(0xFFB2F5EA)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (running) "停止桥接" else "拉起反向桥",
                            fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            color = if (running) Bad else Color(0xFFB2F5EA)
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun Field(
        label: String, value: String, mod: Modifier,
        num: Boolean = false, onChange: (String) -> Unit,
        trailing: (@Composable () -> Unit)? = null
    ) {
        var v by remember { mutableStateOf(value) }
        LaunchedEffect(value) { v = value }
        OutlinedTextField(
            value = v,
            onValueChange = {
                v = it; onChange(it)
                val key = when {
                    label.startsWith("fvps") -> Bridge.K_HOST
                    label.startsWith("停驻") -> Bridge.K_PARK
                    else -> Bridge.K_ADBD
                }
                if (key == Bridge.K_HOST) {
                    Bridge.prefs(this@MainActivity).edit().putString(key, it).apply()
                } else {
                    Bridge.prefs(this@MainActivity).edit()
                        .putInt(key, it.toIntOrNull() ?: 0).apply()
                    if (key == Bridge.K_ADBD)
                        Bridge.adbdPortFlow.value = it.toIntOrNull() ?: 0
                }
            },
            label = { Text(label, fontSize = 11.sp) },
            singleLine = true,
            trailingIcon = trailing,
            keyboardOptions = if (num) KeyboardOptions(keyboardType = KeyboardType.Number)
                              else KeyboardOptions.Default,
            textStyle = LocalTextStyle.current.copy(
                fontFamily = Mono, fontSize = 14.sp),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = Stroke,
                focusedBorderColor = Accent,
                unfocusedLabelColor = Dim,
                focusedLabelColor = Accent,
                unfocusedContainerColor = Color(0xFF0A0F16),
                focusedContainerColor = Color(0xFF0A0F16)
            ),
            modifier = mod
        )
    }

    @Composable
    fun NetCard(adbdPort: String, adbdAlive: Boolean?, adbdSource: String, adbdProbing: Boolean) {
        val ctx = LocalContext.current
        val clip = LocalClipboardManager.current
        val haptic = LocalHapticFeedback.current
        Card(colors = CardDefaults.cardColors(containerColor = Card),
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Stroke)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionTitle(Icons.Filled.Home, "网络")
                var v4 = "-"; var v6 = "-"
                try {
                    for (ni in NetworkInterface.getNetworkInterfaces()) {
                        if (ni.name != "wlan0") continue
                        for (a in ni.inetAddresses) {
                            if (a.isLoopbackAddress) continue
                            if (a is Inet4Address) v4 = a.hostAddress ?: "-"
                            if (a is Inet6Address && !a.isLinkLocalAddress)
                                v6 = a.hostAddress?.substringBefore('%') ?: "-"
                        }
                    }
                } catch (_: Exception) {}
                NetRow(Icons.Filled.Home, "WLAN IPv4", v4, Accent)
                NetRow(Icons.Filled.Home, "WLAN IPv6",
                    v6.take(22) + if (v6.length > 22) "…" else "", Accent)
                NetRow(Icons.Filled.Lock, "Tailnet 节点", "100.113.116.78 (x8u)", Accent)
                // 桌面入口：点击复制
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            clip.setText(AnnotatedString("adb connect 100.126.239.27:2223"))
                            Toast.makeText(ctx, "已复制 adb connect 命令", Toast.LENGTH_SHORT).show()
                        }
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.ArrowForward, null, tint = Dim,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("桌面入口", color = Dim, fontSize = 13.sp,
                        modifier = Modifier.weight(1f))
                    Text("adb connect 100.126.239.27:2223",
                        fontFamily = Mono, fontSize = 12.5.sp, color = Accent)
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.KeyboardArrowRight, null, tint = Dim,
                        modifier = Modifier.size(13.dp))
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Build, null, tint = Dim,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("本机 adbd", color = Dim, fontSize = 13.sp,
                        modifier = Modifier.weight(1f))
                    Text(
                        "127.0.0.1:$adbdPort",
                        fontFamily = Mono, fontSize = 12.5.sp, color = Accent
                    )
                    if (adbdProbing) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Accent.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("侦查中", fontSize = 10.sp, color = Accent,
                                fontWeight = FontWeight.Medium)
                        }
                    } else {
                        val src = when (adbdSource) {
                            "mDNS" -> "mDNS"; "probe" -> "探测"
                            "manual" -> "手动"; else -> "缓存"
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Accent.copy(alpha = 0.12f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(src, fontSize = 10.sp, color = Accent.copy(alpha = 0.85f),
                                fontWeight = FontWeight.Medium)
                        }
                    }
                    if (adbdAlive != null) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    (if (adbdAlive) Good else Bad).copy(alpha = 0.15f)
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                if (adbdAlive) "在线" else "离线",
                                fontSize = 10.sp,
                                color = if (adbdAlive) Good else Bad,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun NetRow(icon: ImageVector, k: String, v: String, vc: Color) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = Dim, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Text(k, color = Dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(v, fontFamily = Mono, fontSize = 12.5.sp, color = vc)
        }
    }

    @Composable
    fun SectionTitle(icon: ImageVector, t: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(t, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Txt)
        }
    }

    @Composable
    fun PrefCard(auto: Boolean, onAuto: (Boolean) -> Unit) {
        Card(colors = CardDefaults.cardColors(containerColor = Card),
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Stroke)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Refresh, null, tint = Accent,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("开机自启", fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp, color = Txt)
                            Text("重启手机后自动拉起桥", color = Dim, fontSize = 11.sp)
                        }
                    }
                    Switch(checked = auto, onCheckedChange = onAuto,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = Accent.copy(alpha = 0.5f),
                            checkedThumbColor = Accent
                        ))
                }
                HorizontalDivider(color = Stroke.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            try {
                                val pm = getSystemService(PowerManager::class.java)
                                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                                    startActivity(
                                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                            .setData(Uri.parse("package:$packageName"))
                                    )
                                } else {
                                    Toast.makeText(this@MainActivity, "已在白名单中",
                                        Toast.LENGTH_SHORT).show()
                                }
                            } catch (_: Exception) {}
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Star, null, tint = Accent,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("电池优化白名单", fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp, color = Txt)
                        Text("防止系统休眠杀掉桥接进程", color = Dim, fontSize = 11.sp)
                    }
                    Icon(Icons.Filled.KeyboardArrowRight, null, tint = Dim,
                        modifier = Modifier.size(18.dp))
                }
            }
        }
    }

    @Composable
    fun LogCard(logs: List<String>) {
        val scroll = rememberScrollState()
        LaunchedEffect(logs.size) { scroll.animateScrollTo(scroll.maxValue) }
        Card(colors = CardDefaults.cardColors(containerColor = Card),
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Stroke)) {
            Column(Modifier.padding(18.dp)) {
                SectionTitle(Icons.Filled.List, "日志")
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF04070B))
                        .border(1.dp, Color(0xFF131A24), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                        .heightIn(min = 100.dp, max = 170.dp)
                ) {
                    Column(Modifier.verticalScroll(scroll)) {
                        for (l in logs.takeLast(40)) {
                            Text(l, fontFamily = Mono,
                                fontSize = 11.sp,
                                color = when {
                                    l.contains("bridged") || l.contains("parked") -> Good
                                    l.contains("fail") || l.contains("end") -> Warn
                                    else -> Color(0xFF8FE3C0)
                                },
                                lineHeight = 16.sp)
                        }
                        if (logs.isEmpty())
                            Text("（无输出）", color = Dim, fontSize = 11.sp,
                                fontFamily = Mono)
                    }
                }
            }
        }
    }
}
