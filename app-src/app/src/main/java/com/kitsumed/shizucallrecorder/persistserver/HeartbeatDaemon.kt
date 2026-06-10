/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.persistserver

import android.os.Process
import java.io.File
import java.io.FileWriter

/**
 * THROWAWAY de-risk spike (CallVault Plan 5, Task 0a).
 *
 * A minimal privileged daemon, launched via `app_process` over the app's embedded ADB shell with
 * `CLASSPATH=<our.apk>`. Its only job is to PROVE survival: it appends a heartbeat line every second
 * to [HEARTBEAT_PATH]. If the line keeps advancing while "Wireless debugging" is turned OFF (which
 * kills `adbd`, and with it the launching adb shell), we have proven the detached server outlives
 * adbd until reboot — exactly how Shizuku's server persists.
 *
 * Detach is performed by [PersistDaemonLauncher] (not here): `setsid` puts us in our own session/
 * process-group and stdio is redirected to /dev/null so EOF/SIGHUP on the closing adb pipe can't
 * reach us. This mirrors Shizuku's native starter:
 *   RikkaApps/Shizuku — manager/src/main/jni/starter.cpp, start_server():
 *     fork() -> child: setsid(); chdir("/"); dup2(open("/dev/null"), STDIN/STDOUT/STDERR);
 *               execvp("/system/bin/app_process", ... CLASSPATH=<apk> <main_class>)
 *   (the parent adb shell then exits without dragging the server down).
 *
 * No Android Context is needed — this is a bare JVM entrypoint run by app_process, which invokes
 * `static void main(String[])`. We keep it tiny, self-contained, and pure java.io.
 */
@androidx.annotation.Keep
object HeartbeatDaemon {

    /** Durable on-device evidence file the device-driver polls while WD is toggled off. */
    private const val HEARTBEAT_PATH = "/data/local/tmp/cv_hb.txt"

    /** Heartbeat cadence. One line per second is plenty to observe "still advancing while WD off". */
    private const val INTERVAL_MS = 1000L

    /**
     * app_process entrypoint. Loops forever, appending one heartbeat line per [INTERVAL_MS].
     * Any fatal error is appended to the evidence file before exiting so failures are diagnosable.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val pid = Process.myPid()
        val uid = Process.myUid()

        // Pre-detach launch logs: visible in the adb shell's drained stdout/stderr BEFORE the
        // launcher closes the pipe. After detach these go to /dev/null (see PersistDaemonLauncher).
        println("HeartbeatDaemon starting pid=$pid uid=$uid -> $HEARTBEAT_PATH")
        System.err.println("HeartbeatDaemon starting pid=$pid uid=$uid")

        try {
            appendLine("START ${System.currentTimeMillis()} pid=$pid uid=$uid session=${args.joinToString(",")}\n")

            var seq = 0L
            while (true) {
                appendLine("$seq ${System.currentTimeMillis()} pid=$pid uid=$uid\n")
                seq++
                Thread.sleep(INTERVAL_MS)
            }
        } catch (t: Throwable) {
            // Append the fatal cause so the driver can see WHY a run died (vs. WD-off survival).
            runCatching {
                appendLine("FATAL ${System.currentTimeMillis()} pid=$pid uid=$uid ${t.javaClass.simpleName}: ${t.message}\n")
            }
            System.err.println("HeartbeatDaemon fatal: $t")
        }
    }

    /** Appends [line] to the evidence file and flushes immediately so it is durable on power loss. */
    private fun appendLine(line: String) {
        FileWriter(File(HEARTBEAT_PATH), /* append = */ true).use { w ->
            w.write(line)
            w.flush()
        }
    }
}
