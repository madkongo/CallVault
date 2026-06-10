/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.persistserver

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import androidx.annotation.Keep
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyAudioCodec
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyAudioMuxer
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyAudioSource
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyClient
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyConfig
import com.kitsumed.shizucallrecorder.utils.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * THROWAWAY de-risk spike (CallVault Plan 5, Task 0b).
 *
 * A bare `app_process` entrypoint (no Android [android.content.Context], shell UID 2000) launched
 * DETACHED by [PersistDaemonLauncher.launchAudioCapture] exactly like the 0a heartbeat daemon, so it
 * survives "Wireless debugging" being turned OFF (which kills `adbd` and the launching adb shell).
 *
 * What it proves together with 0a:
 *  • risk #2 — scrcpy's no-Activity audio workaround firing from a detached daemon, and
 *  • risk #4-lite — the daemon writing a real, playable audio file —
 * by capturing voice-call audio to a `.ogg` while WD is OFF.
 *
 * Pipeline mirrors the deleted `services.ShellService` recording core (git 0d7636b~1), but instead of
 * relaying raw bytes to the app over a pipe, this daemon MUXES LOCALLY:
 *
 *   ┌──────────────────────────────────────────────────────────────────────┐
 *   │  Detached daemon process (UID 2000, no Activity)                      │
 *   │                                                                       │
 *   │  AudioCaptureDaemon.main()                                            │
 *   │    ├── pkill stale scrcpy servers (local Runtime.exec, NOT over ADB)  │
 *   │    ├── LocalServerSocket("scrcpy_<scid>")                             │
 *   │    ├── launches scrcpy-server child via ProcessBuilder(app_process)   │
 *   │    │      CLASSPATH=<scrcpy jar>  (drains its stdout on a thread)      │
 *   │    ├── accept() ── socket.inputStream ──► ScrcpyClient (parses)       │
 *   │    │                                          │                       │
 *   │    │                                          ▼                       │
 *   │    │                                  ScrcpyAudioMuxer ──► <out>.ogg  │
 *   │    ├── status thread: append liveness line to cv_audio_status.txt /1s │
 *   │    └── shutdown hook (SIGTERM): muxer.close() (writes .ogg trailer),  │
 *   │            destroy child, pkill servers, append STOP line             │
 *   └──────────────────────────────────────────────────────────────────────┘
 *
 * Reuses the PRODUCTION scrcpy classes verbatim (no re-implemented parsing/muxing):
 *  • [ScrcpyConfig.getRandomSocketName] / [ScrcpyConfig.SERVER_SOCKET_NAME_PREFIX] /
 *    [ScrcpyConfig.SERVER_MAIN_CLASS] / [ScrcpyConfig.buildServerArgs].
 *  • [ScrcpyClient] (keeps its `SCR:ScrcpyClient: Packet:` logging the device-driver greps).
 *  • [ScrcpyAudioMuxer.initialize] / [ScrcpyAudioMuxer.writePacket] / [ScrcpyAudioMuxer.close].
 *
 * The status file is durable liveness evidence the driver reads AFTER toggling WD back on, because
 * ADB/logcat is dead while WD is OFF.
 *
 * Args (positional): `<serverJarPath> <outOggPath> <audioSourceKey> <audioCodecKey> <bitRate>`.
 */
@Keep
object AudioCaptureDaemon {

    private const val TAG = "SCR:AudioDaemon"

    /** Durable liveness file the device-driver polls AFTER WD is toggled back on (ADB dead during WD-off). */
    private const val STATUS_PATH = "/data/local/tmp/cv_audio_status.txt"

    /** scrcpy-server main-class needle, used to pkill stale servers locally (NOT over ADB). */
    private const val SERVER_MAIN_CLASS_NEEDLE = "com.genymobile.scrcpy.Server"

    /** Status-line cadence: one liveness line per second is plenty to observe "still advancing". */
    private const val STATUS_INTERVAL_MS = 1000L

    /** Grace given to the scrcpy child to flush its final audio frame after [Process.destroy]. */
    private const val PROCESS_STOP_GRACE_MS = 2000L

    /** scrcpy creates its abstract socket on startup; retry-connect until ready (mirrors ScrcpyLauncher). */
    private const val SOCKET_RETRY_COUNT = 60
    private const val SOCKET_RETRY_DELAY_MS = 100L

    /** Running counters shared with the status thread + shutdown hook (cross-thread visible). */
    private val packetCount = AtomicLong(0)
    private val byteCount = AtomicLong(0)

    /** Session resources, nulled when not held. Referenced by [main] and the shutdown hook. */
    @Volatile private var scrcpyProcess: java.lang.Process? = null
    @Volatile private var serverSocket: LocalServerSocket? = null
    @Volatile private var clientConnection: LocalSocket? = null
    @Volatile private var muxer: ScrcpyAudioMuxer? = null
    @Volatile private var scrcpyClient: ScrcpyClient? = null
    @Volatile private var outputStream: FileOutputStream? = null

    /**
     * app_process entrypoint. Runs the capture pipeline until the driver sends SIGTERM (`kill <pid>`),
     * at which point the registered shutdown hook finalises the `.ogg` and tears the child down.
     *
     * @param args `[serverJarPath, outOggPath, audioSourceKey, audioCodecKey, bitRate]`.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val pid = Process.myPid()
        val uid = Process.myUid()

        // Pre-detach logs: visible in the launching adb shell's drained stdout BEFORE the pipe closes;
        // after detach stdio is /dev/null (see PersistDaemonLauncher). Logcat still shows AppLogger.*.
        println("AudioCaptureDaemon starting pid=$pid uid=$uid args=${args.joinToString(",")}")
        AppLogger.i(TAG, "AudioCaptureDaemon starting pid=$pid uid=$uid args=${args.joinToString(",")}")

        if (args.size < 5) {
            appendStatus("FATAL ${System.currentTimeMillis()} pid=$pid bad-args=${args.joinToString(",")}\n")
            AppLogger.e(TAG, "Expected 5 args <serverJarPath> <outOggPath> <audioSourceKey> <audioCodecKey> <bitRate>")
            return
        }

        val serverJarPath = args[0]
        val outOggPath = args[1]
        val audioSource = ScrcpyAudioSource.fromKey(args[2])
        val audioCodec = ScrcpyAudioCodec.fromKey(args[3])
        val bitRate = args[4].toIntOrNull() ?: audioCodec.defaultBitRate

        try {
            appendStatus(
                "START ${System.currentTimeMillis()} pid=$pid uid=$uid out=$outOggPath " +
                    "source=${audioSource.cliKey} codec=${audioCodec.cliKey} bitRate=$bitRate\n"
            )

            // Finalise on SIGTERM: the JVM runs shutdown hooks on SIGTERM, so the driver's `kill <pid>`
            // cleanly writes the .ogg trailer (playable file) and tears the scrcpy child down.
            Runtime.getRuntime().addShutdownHook(Thread { onShutdown(pid) })

            // Clear stale servers locally (we are already shell uid) — a leftover server holds the audio
            // source and makes our launch hang on audio init. Mirrors ScrcpyLauncher.killStaleServers,
            // but run via Runtime.exec instead of over ADB.
            killStaleServersLocally()

            runStatusThread(pid)
            runCapture(serverJarPath, outOggPath, audioSource, audioCodec, bitRate, pid)
        } catch (t: Throwable) {
            runCatching {
                appendStatus("FATAL ${System.currentTimeMillis()} pid=$pid ${t.javaClass.simpleName}: ${t.message}\n")
            }
            AppLogger.e(TAG, "AudioCaptureDaemon fatal: ${t.message}", t)
        }
    }

    /**
     * Builds the local server socket, launches the scrcpy-server child, accepts the connection, and
     * feeds its stream through [ScrcpyClient] into [ScrcpyAudioMuxer]. Blocks until the stream ends.
     *
     * Mirrors `ShellService.startRecording`'s launch+accept+drain+monitor structure; the only change
     * is that the parsed packets are muxed locally to a file instead of relayed over a pipe.
     */
    private fun runCapture(
        serverJarPath: String,
        outOggPath: String,
        audioSource: ScrcpyAudioSource,
        audioCodec: ScrcpyAudioCodec,
        bitRate: Int,
        pid: Int
    ) {
        // Guard: a missing/empty jar would make app_process fail with ClassNotFound — surface it clearly.
        if (!File(serverJarPath).exists()) {
            appendStatus("FATAL ${System.currentTimeMillis()} pid=$pid missing-jar=$serverJarPath\n")
            throw IOException("scrcpy jar missing at $serverJarPath")
        }

        // scrcpy-server is the SOCKET SERVER here (buildServerArgs sets tunnel_forward=true): it creates
        // the abstract socket "scrcpy_<scid>" and waits for a CLIENT. We are that client — mirroring the
        // production ScrcpyLauncher, which connects via openLocalAbstract over ADB. Locally (shell→shell)
        // we connect a LocalSocket to the same abstract name. Creating our OWN LocalServerSocket would
        // collide with scrcpy's ("Address already in use") — the 0b dry-run bug.
        val scid = ScrcpyConfig.getRandomSocketName()
        val fullSocketName = ScrcpyConfig.SERVER_SOCKET_NAME_PREFIX + scid

        // 1. Output muxer — write to a plain shell-owned path (no SAF in a bare daemon). The muxer
        // needs the file's FileDescriptor; keep the FileOutputStream alive so the fd stays valid.
        val out = FileOutputStream(File(outOggPath))
        outputStream = out
        val audioMuxer = ScrcpyAudioMuxer(out.fd, outOggPath)
        muxer = audioMuxer

        // 2. Launch scrcpy-server child (shell→shell, NOT over ADB). CLASSPATH = the scrcpy jar.
        val serverArgs = ScrcpyConfig.buildServerArgs(scid, audioSource, audioCodec, bitRate)
        val launchCommand = mutableListOf("app_process", "/", ScrcpyConfig.SERVER_MAIN_CLASS)
        launchCommand.addAll(serverArgs)
        AppLogger.i(TAG, "Launching scrcpy child: CLASSPATH=$serverJarPath ${launchCommand.joinToString(" ")}")

        val process = ProcessBuilder(launchCommand).apply {
            environment()["CLASSPATH"] = serverJarPath
            redirectErrorStream(true)
        }.start()
        scrcpyProcess = process

        // 3. Drain the child's stdout on a daemon thread (REQUIRED — mirrors ShellService's log
        // consumer; an undrained pipe back-pressures and stalls the child).
        Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { AppLogger.i(TAG, "[scrcpy-server] $it") }
            }
        }.apply { isDaemon = true; name = "scrcpy-stdout" }.start()

        // 4. Connect to scrcpy's abstract socket as a CLIENT, retrying until scrcpy creates it
        // (mirrors ScrcpyLauncher's retry-open loop — scrcpy serves the socket only once audio init starts).
        val address = LocalSocketAddress(fullSocketName, LocalSocketAddress.Namespace.ABSTRACT)
        var connection: LocalSocket? = null
        var tries = 0
        while (connection == null && tries < SOCKET_RETRY_COUNT) {
            runCatching {
                val s = LocalSocket()
                s.connect(address)
                connection = s
            }.onFailure {
                tries++
                Thread.sleep(SOCKET_RETRY_DELAY_MS)
            }
        }
        val conn = connection ?: run {
            appendStatus("FATAL ${System.currentTimeMillis()} pid=$pid socket-never-ready=$fullSocketName\n")
            throw IOException("scrcpy socket never became ready: $fullSocketName")
        }
        clientConnection = conn
        AppLogger.i(TAG, "Connected to scrcpy abstract socket '$fullSocketName' (after $tries retries)")

        // 6. Parse → mux. ScrcpyClient.start() blocks on this thread until EOF/stop; its listener
        // initialises the muxer on metadata and writes each parsed packet. Packet logging
        // ("SCR:ScrcpyClient: Packet:") is emitted inside ScrcpyClient and kept intact.
        val client = ScrcpyClient(
            input = conn.inputStream,
            expectedCodec = audioCodec,
            listener = object : ScrcpyClient.AudioPacketListener {
                override fun onMetadataReceived(codec: ScrcpyAudioCodec) {
                    AppLogger.i(TAG, "Stream metadata: codec=${codec.cliKey} — initialising muxer")
                    audioMuxer.initialize(codec)
                }

                override fun onAudioPacket(packet: ScrcpyClient.AudioPacket) {
                    audioMuxer.writePacket(packet, audioCodec)
                    packetCount.incrementAndGet()
                    byteCount.addAndGet(packet.data.size.toLong())
                }

                override fun onStreamEnd(error: String?) {
                    if (error == null) {
                        AppLogger.i(TAG, "Stream ended cleanly (EOF)")
                    } else {
                        AppLogger.w(TAG, "Stream ended with error: $error")
                    }
                }
            }
        )
        scrcpyClient = client

        client.start() // blocks until the stream ends or the JVM is torn down by SIGTERM.
        AppLogger.i(TAG, "Capture loop finished (packets=${packetCount.get()} bytes=${byteCount.get()})")
    }

    /**
     * Background thread appending a durable liveness line to [STATUS_PATH] every [STATUS_INTERVAL_MS].
     * Format: `"<elapsedSec> packets=<n> bytes=<m> epoch=<ms>\n"`. The driver reads this after WD-on.
     */
    private fun runStatusThread(pid: Int) {
        val startMs = System.currentTimeMillis()
        Thread {
            runCatching {
                while (true) {
                    val elapsedSec = (System.currentTimeMillis() - startMs) / 1000
                    appendStatus(
                        "$elapsedSec packets=${packetCount.get()} bytes=${byteCount.get()} " +
                            "epoch=${System.currentTimeMillis()}\n"
                    )
                    Thread.sleep(STATUS_INTERVAL_MS)
                }
            }.onFailure { AppLogger.w(TAG, "Status thread stopped: ${it.message}") }
        }.apply { isDaemon = true; name = "cv-audio-status" }.start()
    }

    /**
     * Shutdown hook (runs on SIGTERM / `kill <pid>`). Finalises the recording so the `.ogg` trailer is
     * written and the file is playable, then tears the scrcpy child down and clears stale servers.
     * Cleanup order mirrors `ShellService.stopRecording`: stop parsing → destroy child (grace) →
     * close muxer (writes trailer) → close sockets/stream → pkill.
     */
    private fun onShutdown(pid: Int) {
        AppLogger.i(TAG, "Shutdown hook: finalising recording (pid=$pid)")
        runCatching {
            appendStatus(
                "STOP ${System.currentTimeMillis()} pid=$pid packets=${packetCount.get()} " +
                    "bytes=${byteCount.get()}\n"
            )
        }

        // Stop the parser loop first so it releases the socket read.
        runCatching { scrcpyClient?.stop() }

        // Destroy the scrcpy child and give it a moment to flush its final audio frame.
        runCatching { scrcpyProcess?.destroy() }
        runCatching { scrcpyProcess?.waitFor(PROCESS_STOP_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }

        // Close the muxer LAST among writers — MediaMuxer.stop() writes the container trailer, without
        // which the .ogg is structurally incomplete and may not play.
        runCatching { muxer?.close() }
        runCatching { outputStream?.close() }
        runCatching { clientConnection?.close() }
        runCatching { serverSocket?.close() }

        // Clear any lingering server so it doesn't hold the audio source for the next run.
        killStaleServersLocally()
        AppLogger.i(TAG, "Shutdown hook finished")
    }

    /**
     * Kills stale scrcpy-server processes LOCALLY via [Runtime.exec] (we are already shell uid, so no
     * ADB needed). Mirrors `ScrcpyLauncher.killStaleServers` semantics. Best-effort.
     */
    private fun killStaleServersLocally() {
        runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("pkill", "-f", SERVER_MAIN_CLASS_NEEDLE))
            p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            AppLogger.d(TAG, "pkill stale scrcpy-server done (exit=${runCatching { p.exitValue() }.getOrNull()})")
        }.onFailure { AppLogger.w(TAG, "killStaleServersLocally failed: ${it.message}") }
    }

    /** Appends [line] to the status file and flushes immediately so it is durable across WD toggles. */
    private fun appendStatus(line: String) {
        FileWriter(File(STATUS_PATH), /* append = */ true).use { w ->
            w.write(line)
            w.flush()
        }
    }
}
