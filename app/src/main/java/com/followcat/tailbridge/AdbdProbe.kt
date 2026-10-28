package com.followcat.tailbridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import java.net.InetSocketAddress
import java.net.Socket

// Wireless-debugging adbd binds 0.0.0.0, so loopback reaches it on any
// network (WiFi or cellular). mDNS only helps on multicast-capable links,
// which cellular/TUN are not -- so when NSD is blind we find the port by
// scanning loopback, the same trick LADB uses. Two adb flavors exist:
//   TLS   wireless-debugging listener (random high port, TLS first byte)
//   CNXN  legacy listener from `adb tcpip` (fixed, e.g. 5555, ADB proto)
object AdbdProbe {

    // Android picks a random high port for the TLS listener; observed
    // values sit in the ephemeral range. Scan that range first.
    private val RANGE = 30000..65000
    private const val PARALLEL = 512

    // A minimal TLS 1.2 ClientHello: enough for the server to answer with a
    // TLS record (ServerHello or an alert) and identify itself as TLS.
    private val CLIENT_HELLO = byteArrayOf(
        0x16, 0x03, 0x01, 0x00, 0x31, 0x01, 0x00, 0x00, 0x2d, 0x03, 0x03,
        0x54, 0x42, 0x70, 0x72, 0x6f, 0x62, 0x65, 0x21, 0x21, 0x21, 0x21,
        0x21, 0x21, 0x21, 0x21, 0x21, 0x21, 0x21, 0x21, 0x21, 0x21, 0x21,
        0x21, 0x21, 0x21, 0x21, 0x21, 0x21, 0x21, 0x21, 0x21, 0x21,
        0x00, 0x00, 0x04, 0x13, 0x01, 0x13, 0x02, 0x01, 0x00
    )

    // Legacy adb wire probe: a CNXN amessage (6 x LE u32 header + payload).
    // adbd answers CNXN (device banner) or AUTH(TOKEN); the magic field
    // (cmd ^ 0xffffffff) makes the reply self-validating.
    private const val A_CNXN = 0x4e584e43L
    private const val A_AUTH = 0x48545541L

    private fun u32(v: Long): ByteArray = byteArrayOf(
        (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
        ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte()
    )

    private fun cnxnPacket(): ByteArray {
        val payload = "host::".toByteArray()
        val check = payload.fold(0L) { a, b -> a + (b.toLong() and 0xff) }
        return u32(A_CNXN) + u32(0x01000001) + u32(0x100000) +
               u32(payload.size.toLong()) + u32(check) +
               u32(A_CNXN.toLong() xor 0xffffffffL) + payload
    }

    private fun open(port: Int, ms: Int): Socket? = try {
        Socket().apply { connect(InetSocketAddress("127.0.0.1", port), ms) }
    } catch (_: Exception) { null }

    // TLS check: send a ClientHello, the listener must answer with a TLS
    // record (0x16 handshake / 0x15 alert). Plain-text services never do.
    fun isTls(port: Int): Boolean {
        val s = open(port, 800) ?: return false
        return try {
            s.soTimeout = 1200
            s.getOutputStream().write(CLIENT_HELLO)
            s.getOutputStream().flush()
            val b = ByteArray(5)
            val n = s.getInputStream().read(b)
            n >= 2 && (b[0] == 0x16.toByte() || b[0] == 0x15.toByte()) && b[1] == 0x03.toByte()
        } catch (_: Exception) { false } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }

    // Legacy-adb check: send CNXN, expect a reply header whose magic is
    // cmd's bitwise not and whose command is CNXN or AUTH.
    fun isAdb(port: Int): Boolean {
        val s = open(port, 800) ?: return false
        return try {
            s.soTimeout = 1200
            s.getOutputStream().write(cnxnPacket())
            s.getOutputStream().flush()
            val h = ByteArray(24)
            var got = 0
            while (got < 24) {
                val n = s.getInputStream().read(h, got, 24 - got)
                if (n < 0) return false
                got += n
            }
            fun rd(off: Int): Long =
                (h[off].toLong() and 0xff) or
                ((h[off + 1].toLong() and 0xff) shl 8) or
                ((h[off + 2].toLong() and 0xff) shl 16) or
                ((h[off + 3].toLong() and 0xff) shl 24)
            val cmd = rd(0)
            val magic = rd(20)
            (cmd == A_CNXN || cmd == A_AUTH) && magic == (cmd xor 0xffffffffL)
        } catch (_: Exception) { false } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }

    // Null when nothing found. Fast path verifies the stored port first,
    // so the periodic check usually costs a single handshake.
    suspend fun find(current: Int): Int? {
        if (current > 0 && (isAdb(current) || isTls(current))) return current
        val open = openPorts()
        // CNXN-verified listeners are unambiguously adbd; TLS responders
        // are very likely the wireless-debugging port.
        for (p in open) if (isAdb(p)) return p
        for (p in open) if (isTls(p)) return p
        return null
    }

    private suspend fun openPorts(): List<Int> = coroutineScope {
        val sem = Semaphore(PARALLEL)
        val found = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val jobs = RANGE.chunked(400).map { chunk ->
            async(Dispatchers.IO) {
                for (p in chunk) {
                    sem.acquire()
                    try {
                        open(p, 250)?.let { s -> found.add(p); try { s.close() } catch (_: Exception) {} }
                    } finally { sem.release() }
                }
            }
        }
        jobs.awaitAll()
        found.sorted()
    }
}
