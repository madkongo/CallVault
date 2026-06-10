/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.server

import com.kitsumed.shizucallrecorder.BuildConfig
import com.kitsumed.shizucallrecorder.utils.AppLogger
import java.io.File
import java.util.zip.ZipFile

/**
 * CallVault Plan 5 — PRODUCTION scrcpy-server provisioning for the privileged daemon.
 *
 * The detached daemon ([RecorderServer], shell uid 2000) must run scrcpy from a location it can read
 * WITHOUT ADB and WITHOUT `/sdcard` (namespace-flaky for a detached daemon), and that dodges the
 * OxygenOS `/data/local/tmp` ".jar code-reaper". PROVEN solution (spike Task 0b/2):
 *
 *  • The daemon SELF-EXTRACTS the bundled scrcpy-server from its OWN APK by opening [apkPath] as a
 *    [ZipFile] and reading the entry `assets/scrcpy-server` ([BuildConfig.SCRCPY_SERVER_ASSET_NAME]).
 *  • It writes to [JAR_PATH] = `/data/local/tmp/cv-scrcpy.bin` — a NON-`.jar` extension that survives
 *    the OxygenOS reaper; `app_process` CLASSPATH works with any extension (proven on-device).
 *  • Verify-exists + non-empty before launch; re-extract if missing.
 *
 * This replaces the spike's `/sdcard` + ADB `cp` provisioning (PersistDaemonLauncher.launchAudioCapture)
 * with the daemon-self-extract approach the spike proved is reaper-safe and namespace-safe.
 */
internal object ScrcpyJarExtractor {

    private const val TAG = "SCR:RecorderServer"

    /**
     * Reaper-safe, ADB-free, namespace-safe scrcpy location. NON-`.jar` so the OxygenOS
     * `/data/local/tmp` ".jar" code-reaper leaves it alone; app_process CLASSPATH accepts any extension.
     */
    const val JAR_PATH = "/data/local/tmp/cv-scrcpy.bin"

    /** ZipFile entry inside the APK holding the bundled scrcpy-server binary. */
    private val ASSET_ENTRY = "assets/${BuildConfig.SCRCPY_SERVER_ASSET_NAME}"

    /**
     * Ensures [JAR_PATH] holds the scrcpy-server extracted from [apkPath]. Skips extraction if the
     * target already exists and is non-empty (cheap fast-path for warm daemons).
     *
     * @param apkPath the daemon's own APK (passed as a launch arg — `applicationInfo.sourceDir`).
     * @return the absolute path of the extracted scrcpy binary ([JAR_PATH]).
     * @throws java.io.IOException if the asset entry is missing or extraction produced an empty file.
     */
    @Synchronized
    fun ensureScrcpyJar(apkPath: String): String {
        val target = File(JAR_PATH)
        if (target.exists() && target.length() > 0L) {
            AppLogger.d(TAG, "scrcpy already present at $JAR_PATH (${target.length()} bytes); skipping extract")
            return JAR_PATH
        }

        AppLogger.i(TAG, "Self-extracting '$ASSET_ENTRY' from apk=$apkPath -> $JAR_PATH")
        ZipFile(apkPath).use { zip ->
            val entry = zip.getEntry(ASSET_ENTRY)
                ?: throw java.io.IOException("APK is missing scrcpy asset entry '$ASSET_ENTRY' (apk=$apkPath)")
            zip.getInputStream(entry).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }

        if (!target.exists() || target.length() == 0L) {
            throw java.io.IOException("scrcpy extraction produced an empty file at $JAR_PATH")
        }
        AppLogger.i(TAG, "scrcpy extracted to $JAR_PATH (${target.length()} bytes)")
        return JAR_PATH
    }
}
