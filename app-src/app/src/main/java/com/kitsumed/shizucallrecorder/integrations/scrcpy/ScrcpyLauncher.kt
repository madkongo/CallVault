/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
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
     * Closes the audio and shell streams, releasing the ADB connections.
     * Safe to call multiple times — errors are silently swallowed.
     */
    fun stop() {
        runCatching { audioStream.close() }
        runCatching { shellStream.close() }
    }

    companion object {
        private const val TAG = "SCR:ScrcpyLauncher"
        private const val SOCKET_RETRY_COUNT = 100
        private const val SOCKET_RETRY_DELAY_MS = 100L

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
            val sockName = "${ScrcpyConfig.SERVER_SOCKET_NAME_PREFIX}$scid"
            var audio: AdbStream? = null
            repeat(SOCKET_RETRY_COUNT) {
                if (audio == null) {
                    runCatching { audio = AdbShell.openLocalAbstract(context, sockName) }
                    if (audio == null) Thread.sleep(SOCKET_RETRY_DELAY_MS)
                }
            }

            val a = audio ?: run {
                runCatching { shell.close() }
                throw IOException("scrcpy audio socket never became ready: $sockName")
            }

            AppLogger.i(TAG, "scrcpy audio socket connected: $sockName")
            return ScrcpyLauncher(shell, a, a.openInputStream())
        }
    }
}
