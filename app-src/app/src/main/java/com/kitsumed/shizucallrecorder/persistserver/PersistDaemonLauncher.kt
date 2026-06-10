/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.persistserver

import android.content.Context
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.integrations.adb.AdbShell
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyAudioCodec
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyConfig
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ServerExtractor
import com.kitsumed.shizucallrecorder.utils.AppLogger

/**
 * THROWAWAY de-risk spike (CallVault Plan 5, Task 0a).
 *
 * Launches [HeartbeatDaemon] over the app's embedded ADB shell using `CLASSPATH=<our.apk>
 * app_process / <fqcn>` (Shizuku launches its own server identically — CLASSPATH=<apk> app_process
 * <main_class>). The launch is DETACHED so it survives the death of `adbd` (which happens when
 * "Wireless debugging" is turned off), proving Shizuku-style persistence.
 *
 * Mirrors the launch+drain style of [com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyLauncher]:
 * open the shell, drain its stdout briefly so the command is delivered, then close.
 *
 * Detach technique cited from Shizuku's native starter:
 *   RikkaApps/Shizuku — manager/src/main/jni/starter.cpp, start_server() (~L116-142):
 *     fork() -> child: setsid(); chdir("/"); dup2(open("/dev/null"), STDIN/STDOUT/STDERR);
 *               execvp("/system/bin/app_process", ...)
 *   and the backgrounded subshell form in
 *   RikkaApps/Shizuku — starter/.../ServiceStarter.java, commandForUserService():
 *     "(CLASSPATH='%s' %s /system/bin ... %s)&"
 *
 * Why `setsid` is the most reliable over an adb shell: when the launching adb pipe closes (adbd
 * dies on WD-off), the kernel sends SIGHUP to the foreground process-group of that controlling
 * session, and any read/write on the closed pipe yields EOF/EPIPE. `setsid` moves the daemon into
 * a brand-new session with no controlling terminal, so it is NOT in adbd's process-group and never
 * receives that SIGHUP; redirecting stdin/stdout/stderr to /dev/null removes the closed-pipe EOF/
 * EPIPE path entirely. `nohup` only masks SIGHUP (not the process-group/EOF concerns), and a bare
 * `&` leaves the job in adbd's session — hence the [FALLBACK_*] variants below are documented but
 * NOT the primary.
 */
object PersistDaemonLauncher {

    private const val TAG = "SCR:Persist"

    /** Fully-qualified class name app_process invokes (its `static void main(String[])`). */
    const val DAEMON_FQCN = "com.kitsumed.shizucallrecorder.persistserver.HeartbeatDaemon"

    /** 0b audio-capture daemon FQCN app_process invokes (its `static void main(String[])`). */
    const val AUDIO_DAEMON_FQCN = "com.kitsumed.shizucallrecorder.persistserver.AudioCaptureDaemon"

    /**
     * 0c binder command-channel daemon FQCN. Launched DIRECTLY by the device-driver via app_process
     * (NOT from this launcher) so the spike runs with no app process in the loop:
     *   setsid sh -c 'CLASSPATH=<apk> exec app_process / com.kitsumed.shizucallrecorder.persistserver.BinderDebugDaemon' >/dev/null 2>&1 </dev/null &
     */
    const val BINDER_DAEMON_FQCN = "com.kitsumed.shizucallrecorder.persistserver.BinderDebugDaemon"

    /** Shell-owned copy of the scrcpy-server jar, readable by the daemon without scoped-storage hops. */
    private const val SHELL_SCRCPY_JAR = "/data/local/tmp/cv-scrcpy-server.jar"

    /** Output path for the 0b capture (plain shell-owned path, no SAF in a bare daemon). */
    private const val AUDIO_OUT_PATH = "/data/local/tmp/cv_test.ogg"

    /**
     * PRIMARY detached launch for the AUDIO daemon — identical detach technique to [PRIMARY_CMD_FORMAT]
     * (setsid into a fresh session, stdio -> /dev/null, backgrounded) so a closing adb pipe can't
     * SIGHUP/EOF us when WD goes off. `%1$s` = the app APK (CLASSPATH for OUR daemon class); the daemon's
     * OWN child scrcpy uses CLASSPATH=the scrcpy jar internally. `%2$s` = the 5 positional daemon args.
     */
    private const val AUDIO_CMD_FORMAT =
        "setsid sh -c 'CLASSPATH=%1\$s exec app_process / $AUDIO_DAEMON_FQCN %2\$s' >/dev/null 2>&1 </dev/null &"

    /**
     * PRIMARY (tested first): setsid into a fresh session/process-group, stdio -> /dev/null so a
     * closing adb pipe cannot SIGHUP/EOF us, backgrounded. `%s` is the real APK path.
     */
    private const val PRIMARY_CMD_FORMAT =
        "setsid sh -c 'CLASSPATH=%1\$s exec app_process / $DAEMON_FQCN' >/dev/null 2>&1 </dev/null &"

    /**
     * FALLBACK (a) — documented for the device-driver, NOT executed here. `nohup` masks SIGHUP but
     * leaves the job in adbd's session/process-group, so it is less reliable than setsid:
     *   nohup CLASSPATH=<apk> app_process / <fqcn> >/dev/null 2>&1 </dev/null &
     */
    @Suppress("unused")
    const val FALLBACK_A_NOHUP_FORMAT =
        "nohup CLASSPATH=%1\$s app_process / $DAEMON_FQCN >/dev/null 2>&1 </dev/null &"

    /**
     * FALLBACK (b) — documented for the device-driver, NOT executed here. Double-fork: the inner
     * subshell backgrounds the daemon and the outer subshell exits immediately, so the daemon is
     * reparented to init (pid 1) and orphaned away from the adb shell:
     *   sh -c '(CLASSPATH=<apk> app_process / <fqcn> >/dev/null 2>&1 </dev/null &)' &
     */
    @Suppress("unused")
    const val FALLBACK_B_DOUBLE_FORK_FORMAT =
        "sh -c '(CLASSPATH=%1\$s app_process / $DAEMON_FQCN >/dev/null 2>&1 </dev/null &)' &"

    /** How long to drain stdout so the shell command is actually delivered before we close. */
    private const val DRAIN_BUDGET_MS = 1500L

    /**
     * Builds and runs the PRIMARY detached launch command over the embedded ADB connection.
     * Call OFF the main thread (does ADB network I/O and ensureConnected).
     *
     * @param context App context; its `applicationInfo.sourceDir` (the installed APK) is the CLASSPATH.
     */
    fun launch(context: Context) {
        if (!AdbShell.ensureConnected(context)) {
            AppLogger.e(TAG, "ADB not connected; cannot launch persist daemon")
            return
        }

        val apk = context.applicationInfo.sourceDir
        val command = String.format(PRIMARY_CMD_FORMAT, apk)
        AppLogger.i(TAG, "Launching persist daemon. apk=$apk")
        AppLogger.i(TAG, "Persist launch command: $command")

        runCatching {
            AdbShell.openShell(context, command).use { shell ->
                // Drain briefly so the command is delivered, mirroring ScrcpyLauncher. The detached
                // daemon's own stdio is /dev/null, so this only captures the launching shell's exit.
                val deadline = System.currentTimeMillis() + DRAIN_BUDGET_MS
                shell.openInputStream().use { input ->
                    val buf = ByteArray(256)
                    while (System.currentTimeMillis() < deadline) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) AppLogger.d(TAG, "[launch] ${String(buf, 0, n)}")
                    }
                }
            }
            AppLogger.i(TAG, "Persist daemon launch command delivered; shell closed")
        }.onFailure { AppLogger.e(TAG, "Persist daemon launch failed: ${it.message}", it) }
    }

    /**
     * THROWAWAY de-risk spike (CallVault Plan 5, Task 0b).
     *
     * Detached-launches [AudioCaptureDaemon] to prove a no-Activity daemon (shell uid 2000) can capture
     * voice-call audio to a playable `.ogg` while WD is OFF. Call OFF the main thread (ADB network I/O).
     *
     * Steps:
     *  1. Ensure the scrcpy-server jar in the app's shared storage (production [ScrcpyConfig.getServerPath]
     *     + [ServerExtractor.ensureServerFile]).
     *  2. Copy it to a shell-owned path ([SHELL_SCRCPY_JAR]) so the daemon reads it without scoped-storage
     *     hops, and chmod 644. Done over the embedded ADB shell (drained so the copy completes).
     *  3. Delete any prior output and resolve the SAME audio params production uses
     *     (AppPreferences source/codec/bitRate — see AudioRecordingEngine).
     *  4. Detached-launch the daemon with CLASSPATH=the app APK and the 5 positional args.
     *
     * @param context App context; `applicationInfo.sourceDir` (the installed APK) is the daemon CLASSPATH.
     */
    fun launchAudioCapture(context: Context) {
        if (!AdbShell.ensureConnected(context)) {
            AppLogger.e(TAG, "ADB not connected; cannot launch audio-capture daemon")
            return
        }

        // 1. Ensure the scrcpy server jar exists/verified in the app's shared storage.
        val src = ScrcpyConfig.getServerPath(context)
        if (!ServerExtractor.ensureServerFile(context, src)) {
            AppLogger.e(TAG, "scrcpy-server missing/invalid at $src; aborting audio-capture launch")
            return
        }

        // 2. Copy to a shell-owned path so the daemon (shell uid) reads it without scoped-storage hops.
        runShellCommand(context, "cp '$src' $SHELL_SCRCPY_JAR && chmod 644 $SHELL_SCRCPY_JAR")

        // 3. Delete any prior output; resolve the SAME audio params production records calls with
        // (AudioRecordingEngine reads these exact preferences/defaults).
        runShellCommand(context, "rm -f $AUDIO_OUT_PATH")
        val prefs = AppPreferences(context)
        val codecKey = prefs.getAudioCodec()
        val sourceKey = prefs.getAudioSource()
        val bitRate = prefs.getAudioBitRate().takeIf { it > 0 } ?: ScrcpyAudioCodec.fromKey(codecKey).defaultBitRate

        // 4. Build the 5 positional daemon args and the detached launch command.
        val daemonArgs = listOf(SHELL_SCRCPY_JAR, AUDIO_OUT_PATH, sourceKey, codecKey, bitRate.toString())
            .joinToString(" ")
        val apk = context.applicationInfo.sourceDir
        val command = String.format(AUDIO_CMD_FORMAT, apk, daemonArgs)
        AppLogger.i(TAG, "Audio-capture params: source=$sourceKey codec=$codecKey bitRate=$bitRate out=$AUDIO_OUT_PATH")
        AppLogger.i(TAG, "Audio-capture daemon CLASSPATH(apk)=$apk jar=$SHELL_SCRCPY_JAR")
        AppLogger.i(TAG, "Audio-capture launch command: $command")

        runCatching {
            AdbShell.openShell(context, command).use { shell ->
                val deadline = System.currentTimeMillis() + DRAIN_BUDGET_MS
                shell.openInputStream().use { input ->
                    val buf = ByteArray(256)
                    while (System.currentTimeMillis() < deadline) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) AppLogger.d(TAG, "[launch-audio] ${String(buf, 0, n)}")
                    }
                }
            }
            AppLogger.i(TAG, "Audio-capture daemon launch command delivered; shell closed")
        }.onFailure { AppLogger.e(TAG, "Audio-capture daemon launch failed: ${it.message}", it) }
    }

    /**
     * Runs a one-shot [command] over the embedded ADB shell and fully drains it so the command
     * completes before the stream closes (mirrors ScrcpyLauncher's drain-to-completion pattern).
     */
    private fun runShellCommand(context: Context, command: String) {
        runCatching {
            AdbShell.openShell(context, command).use { s ->
                s.openInputStream().use { it.readBytes() }
            }
            AppLogger.d(TAG, "Shell command completed: $command")
        }.onFailure { AppLogger.w(TAG, "Shell command failed ($command): ${it.message}") }
    }
}
