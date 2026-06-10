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
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyAudioCodec
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyAudioSource
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyConfig
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ServerExtractor
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object SpikeActions {

    private const val MDNS_TIMEOUT_MS = 25_000L

    // After connect() returns, libadb's connection reader needs a moment before the
    // first stream can be opened; opening immediately races and adbd drops it
    // ("connection terminated: read failed"). A short settle delay avoids the race.
    private const val CONNECT_SETTLE_MS = 2_500L

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
     * Plan 2 GATE smoke test: launch scrcpy-server over ADB with tunnel_forward=true, connect its
     * localabstract audio socket (retry-open, Tango-style), and report the codec FourCC + bytes read
     * in ~5s. MIC source — works without an active call (shell uid 2000 can capture). Proves the
     * scrcpy-audio-over-ADB transport before any production refactor.
     */
    suspend fun recordScrcpyTest(context: Context): String = withContext(Dispatchers.IO) {
        if (!connect(context)) return@withContext "scrcpy: connect failed".also { SpikeLog.append(it) }
        delay(CONNECT_SETTLE_MS)
        val serverPath = ScrcpyConfig.getServerPath(context)
        if (!ServerExtractor.ensureServerFile(context, serverPath)) {
            return@withContext "scrcpy: server extract failed ($serverPath)".also { SpikeLog.append(it) }
        }
        val scid = ScrcpyConfig.getRandomSocketName()
        // Reuse the proven args; flip to tunnel_forward=true for the ADB localabstract model.
        val args = ScrcpyConfig.buildServerArgs(scid, ScrcpyAudioSource.MIC, ScrcpyAudioCodec.fromKey("opus"), 16000)
            .joinToString(" ").replace("tunnel_forward=false", "tunnel_forward=true")
        val mgr = SpikeAdbManager.getInstance(context)
        SpikeLog.append("→ scrcpy: launching server (scid=$scid) …")
        val shell = mgr.openStream("shell:CLASSPATH=$serverPath app_process / ${ScrcpyConfig.SERVER_MAIN_CLASS} $args")
        // REQUIRED (Tango): drain stdout or it back-pressures the multiplexed ADB connection.
        Thread { runCatching { shell.openInputStream().bufferedReader().forEachLine { SpikeLog.append("[srv] $it") } } }
            .apply { isDaemon = true }.start()
        // Readiness = retry-open the localabstract socket until the server creates it.
        val sockName = "localabstract:${ScrcpyConfig.SERVER_SOCKET_NAME_PREFIX}$scid"
        var audio: AdbStream? = null
        repeat(100) {
            if (audio == null) {
                runCatching { audio = mgr.openStream(sockName) }
                if (audio == null) Thread.sleep(100)
            }
        }
        val a = audio ?: run {
            runCatching { shell.close() }
            return@withContext "scrcpy: audio socket never ready ($sockName)".also { SpikeLog.append(it) }
        }
        val ins = a.openInputStream()
        val header = ByteArray(4)
        var n = 0
        while (n < 4) { val r = ins.read(header, n, 4 - n); if (r < 0) break; n += r }
        val fourcc = header.copyOf(n).joinToString("") { "%02x".format(it) }
        var total = 0L
        val buf = ByteArray(16 * 1024)
        val end = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < end) { val r = ins.read(buf); if (r < 0) break; total += r }
        runCatching { a.close() }; runCatching { shell.close() }
        "scrcpy: header=0x$fourcc bytes=$total".also { SpikeLog.append(it) }
    }

    /**
     * The automated post-pairing sequence: connect → `id` → forward test.
     * Returns a short one/two-line summary suitable for a notification.
     */
    suspend fun runAutoChain(context: Context): String = withContext(Dispatchers.IO) {
        if (!connect(context)) return@withContext "Connect failed — see app log."
        delay(CONNECT_SETTLE_MS)
        val id = shell(context, "Run id", "shell:id")
        shell(context, "Forward test", "shell:cat /proc/uptime")
        val uid = id.substringBefore(' ').ifBlank { "shell" }
        "Connected ✓  ($uid)"
    }
}
