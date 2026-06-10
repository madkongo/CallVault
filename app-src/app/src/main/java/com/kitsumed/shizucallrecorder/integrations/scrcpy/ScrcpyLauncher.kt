/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.integrations.scrcpy

import android.content.Context
import com.kitsumed.shizucallrecorder.integrations.adb.AdbShell
import com.kitsumed.shizucallrecorder.utils.AppLogger
import io.github.muntashirakon.adb.AdbStream
import java.io.IOException
import java.io.InputStream

/**
 * Launches scrcpy-server over the embedded ADB connection (tunnel_forward=true) and exposes
 * the audio stream. Replaces Shizuku's ShellService.startRecording.
 *
 * Proven in the Plan 1/2 spike (SpikeActions.recordScrcpyTest):
 *  1. Launch server via ADB shell using CLASSPATH + app_process.
 *  2. Drain server stdout on a daemon thread to prevent ADB back-pressure.
 *  3. Retry-open the localabstract socket until the server creates it.
 *  4. Return the connected InputStream — first bytes are the 4-byte codec FourCC.
 */
class ScrcpyLauncher private constructor(
    private val shellStream: AdbStream,
    private val audioStream: AdbStream,
    val audioInput: InputStream,
) {
    /**
     * Closes the audio and shell streams, which sends ADB CLSE packets that signal scrcpy-server
     * to exit (otherwise it lingers as an orphan `app_process`).
     *
     * Runs on a background thread: closing performs network I/O over the ADB TLS socket, which
     * throws [android.os.NetworkOnMainThreadException] on the main thread (stop() is often called
     * from the main thread, e.g. RecordingForegroundService.onDestroy / ACTION_STOP_RECORDING).
     * Fire-and-forget — we don't need to block the caller on cleanup. Safe to call multiple times.
     */
    fun stop() {
        Thread {
            runCatching { audioStream.close() }
            runCatching { shellStream.close() }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        private const val TAG = "SCR:ScrcpyLauncher"
        // ~6 s: if the server hasn't served its socket by then it never will (e.g. no live call
        // audio, or a stale server is holding the audio source). Bounded so we fail fast and clean.
        private const val SOCKET_RETRY_COUNT = 60
        private const val SOCKET_RETRY_DELAY_MS = 100L
        private const val SERVER_MAIN_CLASS_NEEDLE = "com.genymobile.scrcpy.Server"

        /**
         * Kills any running scrcpy-server processes via the ADB shell. A leftover server (from an
         * interrupted/failed launch) keeps holding the call-audio source, which makes the NEXT
         * recording's server hang on audio init — so we always clear them before launching and on
         * failure. Best-effort; drains the shell so the command completes.
         */
        fun killStaleServers(context: Context) {
            runCatching {
                AdbShell.openShell(context, "pkill -f $SERVER_MAIN_CLASS_NEEDLE").use { s ->
                    s.openInputStream().use { it.readBytes() }
                }
                AppLogger.d(TAG, "Killed any stale scrcpy-server processes")
            }.onFailure { AppLogger.w(TAG, "killStaleServers failed: ${it.message}") }
        }

        /**
         * Launches scrcpy-server over the embedded ADB connection and returns a [ScrcpyLauncher]
         * whose [audioInput] is ready to read codec-framed audio data.
         *
         * Call off main thread. Throws on failure; caller wraps in a pipeline initialization exception.
         *
         * @param context  App context.
         * @param source   Audio source passed to scrcpy-server (e.g. [ScrcpyAudioSource.VOICE_COMMUNICATION]).
         * @param codec    Audio codec (e.g. [ScrcpyAudioCodec.OPUS]).
         * @param bitRate  Encoding bit rate in bps; pass ≤ 0 to omit (server uses its default).
         * @throws IOException if the server JAR is missing, or the audio socket never becomes ready.
         */
        fun start(
            context: Context,
            source: ScrcpyAudioSource,
            codec: ScrcpyAudioCodec,
            bitRate: Int,
        ): ScrcpyLauncher {
            val serverPath = ScrcpyConfig.getServerPath(context)
            if (!ServerExtractor.ensureServerFile(context, serverPath)) {
                throw IOException("scrcpy-server missing/invalid at $serverPath")
            }

            // Clear any leftover server first — a stale one holds the audio source and makes this
            // launch hang on audio init (the root cause of intermittent "no capture" recordings).
            killStaleServers(context)

            val scid = ScrcpyConfig.getRandomSocketName()
            val args = ScrcpyConfig.buildServerArgs(scid, source, codec, bitRate).joinToString(" ")
            val shell = AdbShell.openShell(
                context,
                "CLASSPATH=$serverPath app_process / ${ScrcpyConfig.SERVER_MAIN_CLASS} $args"
            )

            // REQUIRED: drain stdout continuously or it back-pressures the multiplexed ADB connection.
            // Proven in the spike — omitting this stalls the entire ADB session.
            Thread {
                runCatching {
                    shell.openInputStream().bufferedReader().forEachLine { AppLogger.i(TAG, "[srv] $it") }
                }
            }.apply { isDaemon = true }.start()

            // Readiness via retry-open: openLocalAbstract fails until the server creates the socket.
            // Interruptible so a call that ends mid-launch (coroutine cancel) stops promptly.
            val sockName = "${ScrcpyConfig.SERVER_SOCKET_NAME_PREFIX}$scid"
            var audio: AdbStream? = null
            var tries = 0
            while (audio == null && tries < SOCKET_RETRY_COUNT && !Thread.currentThread().isInterrupted) {
                runCatching { audio = AdbShell.openLocalAbstract(context, sockName) }
                if (audio == null) {
                    tries++
                    try {
                        Thread.sleep(SOCKET_RETRY_DELAY_MS)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }

            val a = audio ?: run {
                // Server never served the socket — kill it so it doesn't linger and block the next call.
                runCatching { shell.close() }
                killStaleServers(context)
                throw IOException("scrcpy audio socket never became ready: $sockName")
            }

            AppLogger.i(TAG, "scrcpy audio socket connected: $sockName")
            return ScrcpyLauncher(shell, a, a.openInputStream())
        }
    }
}
