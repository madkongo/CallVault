/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.app.Application
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import com.baba.callvault.utils.AppLogger

/**
 * Gives the recorder host an Application that is the shell, for ROMs whose audio code insists on one.
 *
 * Issue #53: Honor's MagicOS 11 (Android 17) starts `AudioRecord.startRecording()` with
 * `Objects.requireNonNull(ActivityThread.currentApplication())`, then reads the app's label for an audit log.
 * Our hosts have no Application — the `app_process` daemon never had one, and [ShellAttribution] clears the one
 * Shizuku loads — so every start threw before recording began.
 *
 * Installing OUR Application would bring back the uid 2000 + `com.baba.callvault` pair that AppOps refuses
 * (see [ShellAttribution]), so this installs one whose package and attribution are the shell's. It is the
 * approach scrcpy's `Workarounds` + `FakeContext` take in the same kind of process (Apache-2.0, Genymobile).
 *
 * Only called after that crash was seen ([HostAudioRecord.start]): every other phone keeps the identity it has
 * today. Process-global and one-way.
 */
internal object ShellApplication {

    private const val TAG = "CV:ShellApplication"
    private const val SHELL_PACKAGE = "com.android.shell"
    private const val SHELL_UID = 2000

    @Volatile
    private var installed = false

    /** @return true when `ActivityThread.currentApplication()` now returns a shell Application. */
    @Synchronized
    fun install(): Boolean {
        if (installed) return true
        return runCatching {
            val atCls = Class.forName("android.app.ActivityThread")
            val thread = atCls.getMethod("currentActivityThread").invoke(null) ?: newActivityThread(atCls)
            val system = atCls.getDeclaredMethod("getSystemContext").apply { isAccessible = true }
                .invoke(thread) as Context
            val app = Host().apply { attach(ShellContext(system)) }
            atCls.getDeclaredField("mInitialApplication").apply { isAccessible = true }.set(thread, app)
            installed = true
            AppLogger.i(TAG, "Installed a shell Application for the ROM's AudioRecord checks")
            true
        }.onFailure { AppLogger.e(TAG, "Could not install a shell Application: ${it.message}", it) }
            .getOrDefault(false)
    }

    /** What scrcpy does when the process has no ActivityThread: make one and mark it current. */
    private fun newActivityThread(atCls: Class<*>): Any {
        val thread = atCls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        atCls.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
        atCls.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(thread, true)
        return thread
    }

    private class Host : Application() {
        fun attach(base: Context) = attachBaseContext(base)
    }

    /** The system context, presenting itself as the shell package. */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = SHELL_PACKAGE
        override fun getOpPackageName(): String = SHELL_PACKAGE
        override fun getApplicationContext(): Context = this

        /**
         * The process's real uid (review N21). scrcpy's FakeContext always claims uid 2000, but a Shizuku server
         * started with root runs this host as uid 0, and it reaches [install] through the same AudioRecord paths.
         * Only the shell uid presents the shell package; any other uid presents none, which the AudioRecord
         * constructor names "uid:<uid>" — what that host presents today with no Context at all.
         */
        @RequiresApi(Build.VERSION_CODES.S)
        override fun getAttributionSource(): AttributionSource {
            val uid = Process.myUid()
            return AttributionSource.Builder(uid).setPackageName(attributionPackageFor(uid)).build()
        }
    }

    /** The package the attribution names for [uid]: the shell's for the shell uid, none for any other. */
    internal fun attributionPackageFor(uid: Int): String? = if (uid == SHELL_UID) SHELL_PACKAGE else null
}
