package com.followcat.tailbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

class BridgeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchdog: Job? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var mcastLock: WifiManager.MulticastLock? = null

    companion object {
        const val CH = "bridge"
        const val NID = 42
        const val ACT_START = "com.followcat.tailbridge.START"
        const val ACT_STOP = "com.followcat.tailbridge.STOP"

        fun start(c: Context) {
            val i = Intent(c, BridgeService::class.java).setAction(ACT_START)
            c.startForegroundService(i)
        }
        fun stop(c: Context) {
            c.startService(Intent(c, BridgeService::class.java).setAction(ACT_STOP))
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_STOP -> { shutdown(); return START_NOT_STICKY }
            else -> {
                Bridge.wantRunning = true
                foreground("TailBridge 启动中")
                startNsd()
                startWatchdog()
            }
        }
        return START_STICKY
    }

    private fun foreground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH, "ADB 桥", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = Notification.Builder(this, CH)
            .setContentTitle("TailBridge")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NID, n)
        }
    }

    private fun updateNotif() {
        val text = when (Bridge.state.value) {
            BridgeState.BRIDGED -> "ADB 会话桥接中"
            BridgeState.PARKED -> "已停驻 · 等待桌面连接"
            BridgeState.CONNECTING -> "重连中…"
            BridgeState.STOPPED -> "已停止"
        }
        foreground(text)
    }

    private fun binaryPath(): String {
        // App-private dirs are mounted noexec for targetSdk>=30; executables
        // must live in nativeLibraryDir (shipped as a fake .so in jniLibs).
        return applicationInfo.nativeLibraryDir + "/libadbstitch.so"
    }

    private fun spawn() {
        val bin = binaryPath()
        val pb = ProcessBuilder(
            bin,
            Bridge.host(this),
            Bridge.parkPort(this).toString(),
            Bridge.adbdPort(this).toString()
        ).redirectErrorStream(true)
        Bridge.proc = pb.start()
        Bridge.childAlive.value = true
        Bridge.state.value = BridgeState.CONNECTING
        Bridge.pushLog("spawned adbstitch")
    }

    private fun ownAddrs(): Set<String> {
        val s = mutableSetOf("127.0.0.1", "::1")
        try {
            for (ni in NetworkInterface.getNetworkInterfaces())
                for (a in ni.inetAddresses) a.hostAddress?.substringBefore('%')?.let { s.add(it) }
        } catch (_: Exception) {}
        return s
    }

    private fun startNsd() {
        if (discovery != null) return
        val nsd = getSystemService(NsdManager::class.java) ?: return
        try {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            mcastLock = wm.createMulticastLock("tailbridge").apply {
                setReferenceCounted(true); acquire()
            }
        } catch (_: Exception) {}
        val d = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                try {
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(i: NsdServiceInfo, e: Int) {}
                        override fun onServiceResolved(i: NsdServiceInfo) {
                            val host = i.host?.hostAddress?.substringBefore('%') ?: return
                            if (host !in ownAddrs()) return   // only self's adbd
                            val p = i.port
                            if (p <= 0) return
                            val cur = Bridge.adbdPort(this@BridgeService)
                            if (p != cur) {
                                Bridge.pushLog("mDNS: adbd port $cur -> $p")
                                Bridge.prefs(this@BridgeService).edit()
                                    .putInt(Bridge.K_ADBD, p).apply()
                                Bridge.adbdPortFlow.value = p
                                if (Bridge.wantRunning) {
                                    Bridge.pushLog("port changed, respawning")
                                    try { Bridge.proc?.destroy() } catch (_: Exception) {}
                                }
                            }
                        }
                    })
                } catch (_: Exception) {}
            }
            override fun onServiceLost(i: NsdServiceInfo) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
        }
        discovery = d
        try {
            nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, d)
            Bridge.pushLog("mDNS discovering adbd")
        } catch (e: Exception) {
            Bridge.pushLog("nsd: ${e.message}")
        }
    }

    private fun stopNsd() {
        discovery?.let {
            try { getSystemService(NsdManager::class.java)?.stopServiceDiscovery(it) }
            catch (_: Exception) {}
        }
        discovery = null
        try { mcastLock?.release() } catch (_: Exception) {}
        mcastLock = null
    }

    private fun startWatchdog() {
        if (watchdog?.isActive == true) return
        watchdog = scope.launch {
            // adbd liveness probe: 127.0.0.1:<port> connect attempt
            launch {
                while (isActive) {
                    val port = Bridge.adbdPort(this@BridgeService)
                    Bridge.adbdAlive.value = try {
                        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 900) }
                        true
                    } catch (_: Exception) { false }
                    delay(8000)
                }
            }
            // reader: stream child output into the log/state
            launch {
                while (isActive) {
                    val p = Bridge.proc
                    if (p == null) { delay(500); continue }
                    try {
                        val r = p.inputStream.bufferedReader()
                        var line = r.readLine()
                        while (isActive && line != null) {
                            Bridge.pushLog(line)
                            updateNotif()
                            line = r.readLine()
                        }
                    } catch (_: Exception) {}
                    delay(500)
                }
            }
            // watchdog: (re)spawn while wanted
            while (isActive) {
                val p = Bridge.proc
                val dead = p == null || !p.isAlive
                Bridge.childAlive.value = p?.isAlive == true
                if (Bridge.wantRunning && dead) {
                    try { p?.destroy() } catch (_: Exception) {}
                    Bridge.state.value = BridgeState.CONNECTING
                    updateNotif()
                    try { spawn() } catch (e: Exception) {
                        Bridge.pushLog("spawn fail: ${e.message}")
                    }
                }
                delay(1500)
            }
        }
    }

    private fun shutdown() {
        Bridge.wantRunning = false
        try { Bridge.proc?.destroy() } catch (_: Exception) {}
        Bridge.proc = null
        Bridge.childAlive.value = false
        Bridge.state.value = BridgeState.STOPPED
        Bridge.adbdAlive.value = null
        watchdog?.cancel(); watchdog = null
        stopNsd()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { shutdown(); super.onDestroy() }
}
