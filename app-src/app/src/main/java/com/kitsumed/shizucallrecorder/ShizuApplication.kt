/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder

import android.app.Application
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.integrations.adb.AdbShell
import com.kitsumed.shizucallrecorder.utils.AppLogger

/**
 * ShizuApplication is run when the app process is created. Can be seen as the very first entry point of the app.
 */
class ShizuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(applicationContext)

        // If ADB was already paired, warm up the connection in the background so the app is ready
        // to record without re-running setup each launch. Best-effort; the recording path also
        // calls AdbShell.ensureConnected on demand. Network I/O → off the main thread.
        if (AppPreferences(applicationContext).isAdbPaired()) {
            Thread {
                runCatching { AdbShell.ensureConnected(applicationContext) }
            }.apply { isDaemon = true }.start()
        }
    }
}