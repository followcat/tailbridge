package com.followcat.tailbridge

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

enum class BridgeState { STOPPED, CONNECTING, PARKED, BRIDGED }

object Bridge {
    const val PREF = "tailbridge"
    const val K_HOST = "remote_host"
    const val K_PARK = "park_port"
    const val K_ADBD = "adbd_port"
    const val K_AUTO = "autostart"
    const val K_MANUAL = "manual_port"

    var proc: Process? = null
    @Volatile var wantRunning = false

    val state = MutableStateFlow(BridgeState.STOPPED)
    val log = MutableStateFlow<List<String>>(emptyList())
    val childAlive = MutableStateFlow(false)
    val adbdAlive = MutableStateFlow<Boolean?>(null)
    val adbdPortFlow = MutableStateFlow(0)
    // How the current adbd port was learned: mDNS, loopback probe, stored.
    val adbdPortSource = MutableStateFlow("stored")
    val adbdProbing = MutableStateFlow(false)
    // User-typed port wins: while locked, mDNS/probe must not overwrite it.
    val manualPort = MutableStateFlow(false)

    fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun host(c: Context) = prefs(c).getString(K_HOST, "100.126.239.27") ?: "100.126.239.27"
    fun parkPort(c: Context) = prefs(c).getInt(K_PARK, 2222)
    fun adbdPort(c: Context): Int {
        val p = prefs(c).getInt(K_ADBD, 46007)
        if (adbdPortFlow.value != p) adbdPortFlow.value = p
        return p
    }
    fun autostart(c: Context) = prefs(c).getBoolean(K_AUTO, true)
    fun manualPort(c: Context): Boolean {
        val m = prefs(c).getBoolean(K_MANUAL, false)
        if (manualPort.value != m) manualPort.value = m
        return m
    }

    fun pushLog(line: String) {
        val t = line.trim()
        if (t.isEmpty()) return
        log.value = (log.value + t).takeLast(60)
        state.value = when {
            t.contains("splice first") || t.contains("bridged") -> BridgeState.BRIDGED
            t.contains("parked") -> BridgeState.PARKED
            t.contains("park end") || t.contains("pump end") ||
            t.contains("idle-timeout") || t.contains("dial fail") -> BridgeState.CONNECTING
            else -> state.value
        }
    }
}
