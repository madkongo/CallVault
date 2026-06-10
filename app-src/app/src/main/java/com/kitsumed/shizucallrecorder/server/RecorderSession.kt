/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.server

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyAudioCodec
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyAudioMuxer
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyAudioSource
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyClient
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyConfig
import com.kitsumed.shizucallrecorder.utils.AppLogger
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * CallVault Plan 5 — PRODUCTION single recording session run by the privileged daemon.
 *
 * Owns ONE scrcpy capture pipeline: scrcpy-server child + a CLIENT [LocalSocket] (scrcpy serves the
 * abstract socket via tunnel_forward=true; we connect with retry — NOT a LocalServerSocket, which
 * collides → "Address already in use", the spike's 0b dry-run bug) + [ScrcpyClient] → [ScrcpyAudioMuxer]
 * into the APP-provided output fd.
 *
 * Ported from the proven spike (persistserver/AudioCaptureDaemon.runCapture/onShutdown) and mirrors
 * the production [com.kitsumed.shizucallrecorder.services.recording.AudioRecordingEngine] start/release
 * ordering. The ONE production difference vs the engine: the daemon muxes into a binder-delivered fd
 * (the APP made the SAF file + pfd) instead of opening its own FileOutputStream.
 *
 * Not thread-safe by itself; [RecorderServer] guards start/stop with an AtomicBoolean and serialises
 * lifecycle calls onto a single worker thread.
 */
internal class RecorderSession(
    private val source: ScrcpyAudioSource,
    private val codec: ScrcpyAudioCodec,
    private val bitRate: Int,
    /** The daemon's received fd copy. Muxer writes through it; closed by [stop] after muxer.close(). */
    private val outFd: ParcelFileDescriptor,
    private val scrcpyJarPath: String
) {

    private companion object {
        private const val TAG = "SCR:RecorderServer"

        /** scrcpy-server main-class needle, used to pkill stale servers locally (NOT over ADB). */
        private const val SERVER_MAIN_CLASS_NEEDLE = "com.genymobile.scrcpy.Server"

        /** Grace given to the scrcpy child to flush its final audio frame after destroy(). */
        private const val PROCESS_STOP_GRACE_MS = 2000L

        /** Brief wait for the read thread to drain late bytes during stop (mirrors engine release()). */
        private const val READ_DRAIN_WAIT_MS = 2000L

        /** scrcpy creates its abstract socket on startup; retry-connect until ready (mirrors launcher). */
        private const val SOCKET_RETRY_COUNT = 60
        private const val SOCKET_RETRY_DELAY_MS = 100L
    }

    @Volatile private var scrcpyProcess: java.lang.Process? = null
    @Volatile private var clientConnection: LocalSocket? = null
    @Volatile private var muxer: ScrcpyAudioMuxer? = null
    @Volatile private var scrcpyClient: ScrcpyClient? = null
    @Volatile private var readThread: Thread? = null

    /**
     * Launches the scrcpy child, connects the client socket, and starts parsing → muxing on a
     * dedicated read thread. Returns once the pipeline is running; the read thread blocks until EOF
     * or [stop]. Mirrors `ShellService.startRecording`/`AudioCaptureDaemon.runCapture` structure.
     *
     * @throws IOException if the scrcpy jar is missing or the abstract socket never becomes ready.
     */
    fun start() {
        // Guard: a missing/empty jar would make app_process fail with ClassNotFound — surface it clearly.
        if (!File(scrcpyJarPath).exists()) {
            throw IOException("scrcpy jar missing at $scrcpyJarPath")
        }

        // Clear stale servers locally (we are already shell uid) — a leftover server holds the audio
        // source and makes our launch hang on audio init. Mirrors ScrcpyLauncher.killStaleServers,
        // run via Runtime.exec instead of over ADB.
        killStaleServersLocally()

        // scrcpy-server is the SOCKET SERVER (buildServerArgs sets tunnel_forward=true): it creates the
        // abstract socket "scrcpy_<scid>" and waits for a CLIENT. We are that client. Creating our OWN
        // LocalServerSocket would collide ("Address already in use") — the proven 0b dry-run bug.
        val scid = ScrcpyConfig.getRandomSocketName()
        val fullSocketName = ScrcpyConfig.SERVER_SOCKET_NAME_PREFIX + scid

        // 1. Output muxer over the APP-provided fd (the app made the SAF file + pfd and passed it over
        // binder; the privileged daemon writes through it). "recording" is just the log display name.
        val audioMuxer = ScrcpyAudioMuxer(outFd.fileDescriptor, "recording")
        muxer = audioMuxer

        // 2. Launch scrcpy-server child (shell→shell, NOT over ADB). CLASSPATH = the extracted scrcpy bin.
        val serverArgs = ScrcpyConfig.buildServerArgs(scid, source, codec, bitRate)
        val launchCommand = mutableListOf("app_process", "/", ScrcpyConfig.SERVER_MAIN_CLASS)
        launchCommand.addAll(serverArgs)
        AppLogger.i(TAG, "Launching scrcpy child: CLASSPATH=$scrcpyJarPath ${launchCommand.joinToString(" ")}")

        val process = ProcessBuilder(launchCommand).apply {
            environment()["CLASSPATH"] = scrcpyJarPath
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
        val conn = connectWithRetry(fullSocketName)
        clientConnection = conn
        AppLogger.i(TAG, "Connected to scrcpy abstract socket '$fullSocketName'")

        // 5. Parse → mux. ScrcpyClient.start() blocks until EOF/stop; the listener initialises the muxer
        // on metadata and writes each parsed packet (the EXACT pattern from AudioRecordingEngine /
        // AudioCaptureDaemon). ScrcpyClient keeps its "SCR:ScrcpyClient: Packet:" logging internally.
        val client = ScrcpyClient(
            input = conn.inputStream,
            expectedCodec = codec,
            listener = object : ScrcpyClient.AudioPacketListener {
                override fun onMetadataReceived(streamCodec: ScrcpyAudioCodec) {
                    AppLogger.i(TAG, "Stream metadata: codec=${streamCodec.cliKey} — initialising muxer")
                    audioMuxer.initialize(streamCodec)
                }

                override fun onAudioPacket(packet: ScrcpyClient.AudioPacket) {
                    audioMuxer.writePacket(packet, codec)
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

        // Run the blocking parse loop on its own thread so start() returns to the binder worker.
        readThread = Thread {
            runCatching { client.start() }
                .onFailure { AppLogger.w(TAG, "Capture read loop ended: ${it.message}") }
        }.apply { isDaemon = true; name = "scrcpy-read" }.also { it.start() }
    }

    /**
     * Tears the session down. Order mirrors `ShellService.stopRecording` / `AudioCaptureDaemon.onShutdown`
     * / `AudioRecordingEngine.release`: stop the client → destroy the scrcpy child with grace → wait the
     * read thread briefly for late bytes → close the muxer (writes the container trailer) → close the
     * socket → close the daemon's received outFd copy → pkill stale. Best-effort throughout. Idempotent
     * (the caller's AtomicBoolean ensures one invocation; each step is independently guarded anyway).
     */
    fun stop() {
        AppLogger.i(TAG, "Stopping recording session")

        // Stop the parser loop first so it releases the socket read.
        runCatching { scrcpyClient?.stop() }

        // Destroy the scrcpy child and give it a moment to flush its final audio frame.
        runCatching { scrcpyProcess?.destroy() }
        runCatching { scrcpyProcess?.waitFor(PROCESS_STOP_GRACE_MS, TimeUnit.MILLISECONDS) }

        // Wait briefly for the read thread to drain any late bytes before we finalise the muxer.
        runCatching { readThread?.join(READ_DRAIN_WAIT_MS) }

        // Close the muxer LAST among writers — MediaMuxer.stop() writes the container trailer, without
        // which the file is structurally incomplete and may not play.
        runCatching { muxer?.close() }
        runCatching { clientConnection?.close() }

        // Close the daemon's OWN received pfd copy. The app closes its own copy separately (proven: the
        // pfd survives the binder hop as an independent fd per process).
        runCatching { outFd.close() }

        // Clear any lingering server so it doesn't hold the audio source for the next run.
        killStaleServersLocally()
        AppLogger.i(TAG, "Recording session stopped")
    }

    /** Connects a CLIENT [LocalSocket] to the abstract [name] with retry until scrcpy serves it. */
    private fun connectWithRetry(name: String): LocalSocket {
        val address = LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT)
        var tries = 0
        while (tries < SOCKET_RETRY_COUNT) {
            val connected = runCatching {
                LocalSocket().also { it.connect(address) }
            }.getOrNull()
            if (connected != null) {
                AppLogger.d(TAG, "Connected to '$name' after $tries retries")
                return connected
            }
            tries++
            Thread.sleep(SOCKET_RETRY_DELAY_MS)
        }
        throw IOException("scrcpy socket never became ready: $name")
    }

    /**
     * Kills stale scrcpy-server processes LOCALLY via [Runtime.exec] (we are already shell uid, so no
     * ADB needed). Mirrors `ScrcpyLauncher.killStaleServers` semantics. Best-effort.
     */
    private fun killStaleServersLocally() {
        runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("pkill", "-f", SERVER_MAIN_CLASS_NEEDLE))
            p.waitFor(2, TimeUnit.SECONDS)
            AppLogger.d(TAG, "pkill stale scrcpy-server done (exit=${runCatching { p.exitValue() }.getOrNull()})")
        }.onFailure { AppLogger.w(TAG, "killStaleServersLocally failed: ${it.message}") }
    }
}
