/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import android.app.ActivityManager
import android.app.ApplicationStartInfo
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.telecom.CallRedirectionService
import android.telecom.PhoneAccountHandle
import com.baba.callvault.utils.AppLogger
import java.util.concurrent.atomic.AtomicInteger

/**
 * A TEST, opt-in (Settings > Help and about > "Test: hold the Call redirecting role"). It exists only so a reporter's
 * phone can tell us whether holding the "Call redirecting app" role is safe and useful for CallVault (BabApps item 111).
 *
 * It never redirects and never cancels: [onPlaceCall] calls `placeCallUnmodified()` first and unconditionally (see
 * [CallRedirectAnswer]). Telecom cancels the user's outgoing call when a call redirection app does not answer in 5 s,
 * which is the whole risk of this role, so the answer comes before anything is measured.
 *
 * It writes one always-on line per call to [CallRedirectDiagLog]: how the process started, bind -> onPlaceCall,
 * the time `placeCallUnmodified()` took, and the PhoneAccount Telecom handed over. CallVault's recording does not use it.
 */
class CallRedirectDiagService : CallRedirectionService() {

    private companion object {
        const val TAG = "CV:CallRedirectDiag"
        /** Binds seen by this process: 1 on the bind that is also the first thing the process does. */
        val bindsInProcess = AtomicInteger(0)
    }

    @Volatile private var boundAtMs = SystemClock.elapsedRealtime()

    // onBind is final in CallRedirectionService, so the bind is timed from onCreate: Telecom binds with BIND_AUTO_CREATE
    // and unbinds when the call is placed, so the service is created by the bind it then answers.
    override fun onCreate() {
        super.onCreate()
        boundAtMs = SystemClock.elapsedRealtime()
        // Nothing here may stop the bind: a failed bind cancels the call after 5 s.
        runCatching {
            val first = bindsInProcess.incrementAndGet() == 1
            // Process age: a cold start (Telecom had to create our process for this call) is a few seconds old at most.
            val ageMs = runCatching { boundAtMs - Process.getStartElapsedRealtime() }.getOrDefault(-1L)
            write(
                "bind(service created): processStart=${if (first && ageMs in 0..15_000) "COLD(likely)" else if (first) "first-bind-in-old-process" else "WARM"} " +
                    "processAge=${ageMs}ms bindsInProcess=${bindsInProcess.get()} ${startInfo()}",
            )
        }
    }

    override fun onPlaceCall(handle: Uri, initialPhoneAccount: PhoneAccountHandle, allowInteractiveResponse: Boolean) {
        CallRedirectAnswer.answer(
            boundAtMs = boundAtMs,
            now = SystemClock::elapsedRealtime,
            place = { placeCallUnmodified() },
            describe = { "phoneAccount=${describe(initialPhoneAccount)} interactive=$allowInteractiveResponse number=${shape(handle)}" },
            log = ::write,
        )
    }

    private fun write(line: String) {
        CallRedirectDiagLog.record(applicationContext, line)
        AppLogger.i(TAG, line)
    }

    /** Android 15+: how the system says it started this process (reason and cold/warm). Empty before that. */
    private fun startInfo(): String {
        if (Build.VERSION.SDK_INT < 35) return "startInfo=n/a(sdk<35)"
        return runCatching {
            val am = getSystemService(ActivityManager::class.java)
            val info = am.getHistoricalProcessStartReasons(1).firstOrNull() ?: return "startInfo=none"
            val type = when (info.startType) {
                ApplicationStartInfo.START_TYPE_COLD -> "cold"
                ApplicationStartInfo.START_TYPE_WARM -> "warm"
                ApplicationStartInfo.START_TYPE_HOT -> "hot"
                else -> "type${info.startType}"
            }
            "startInfo=reason${info.reason}/$type"
        }.getOrElse { "startInfo=error(${it.javaClass.simpleName})" }
    }

    /** The component and a short fingerprint of the account id, never the id itself (it can carry a SIM identifier). */
    private fun describe(h: PhoneAccountHandle?): String =
        if (h == null) "none"
        else "${h.componentName.flattenToShortString()} idLen=${h.id.length} idHash=${Integer.toHexString(h.id.hashCode())}"

    /** Never the number: only its kind and how long it is. */
    private fun shape(handle: Uri): String =
        "${handle.scheme ?: "?"}:${handle.schemeSpecificPart?.count { it.isDigit() } ?: 0}digits"
}
