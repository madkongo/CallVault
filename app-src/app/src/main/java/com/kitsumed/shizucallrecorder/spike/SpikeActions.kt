/*
 * SpikeActions — the ADB operations the spike runs (connect / shell / the post-pair
 * auto-chain), shared by the Activity buttons and the pairing service so pairing can
 * automatically continue into connect → id → forward. Every step appends to [SpikeLog].
 *
 * All functions block on I/O and MUST be called off the main thread; [runAutoChain]
 * does that for you via [withContext].
 *
 * DEBUG SPIKE: remove before production.
 */

package com.kitsumed.shizucallrecorder.spike

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SpikeActions {

    private const val MDNS_TIMEOUT_MS = 25_000L

    /** Discovers the _adb-tls-connect._tcp port over mDNS and connects with the persisted key. */
    fun connect(context: Context): Boolean {
        SpikeLog.append("→ Connect (mDNS) …")
        val port = AdbMdns.discoverPort(context, AdbMdns.TLS_CONNECT, MDNS_TIMEOUT_MS)
        if (port == null) {
            SpikeLog.append("connect: no _adb-tls-connect._tcp found — is Wireless debugging ON?")
            return false
        }
        return try {
            val ok = SpikeAdbManager.getInstance(context).connect("127.0.0.1", port)
            SpikeLog.append("connect(127.0.0.1:$port) → $ok")
            ok
        } catch (e: Exception) {
            SpikeLog.append("connect ERROR: ${e.message}")
            false
        }
    }

    /** Runs one ADB shell command, appends its output, and returns the trimmed output (or error text). */
    fun shell(context: Context, label: String, command: String): String {
        SpikeLog.append("→ $label …")
        return try {
            val out = SpikeAdbManager.getInstance(context).openStream(command).use { s ->
                s.openInputStream().use { it.readBytes().toString(Charsets.UTF_8) }
            }.trim()
            SpikeLog.append(out)
            out
        } catch (e: Exception) {
            val msg = "$label ERROR: ${e.message}"
            SpikeLog.append(msg)
            msg
        }
    }

    /**
     * The automated post-pairing sequence: connect → `id` → forward test.
     * Returns a short one/two-line summary suitable for a notification.
     */
    suspend fun runAutoChain(context: Context): String = withContext(Dispatchers.IO) {
        if (!connect(context)) return@withContext "Connect failed — see app log."
        val id = shell(context, "Run id", "shell:id")
        shell(context, "Forward test", "shell:cat /proc/uptime")
        val uid = id.substringBefore(' ').ifBlank { "shell" }
        "Connected ✓  ($uid)"
    }
}
