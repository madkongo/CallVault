/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.server

import android.os.IBinder
import com.kitsumed.shizucallrecorder.utils.AppLogger

/**
 * CallVault Plan 5, Task 3 — PRODUCTION app-side connection holder.
 *
 * Process-wide singleton holding the privileged daemon's [IRecorderService] after it is delivered
 * to [RecorderBinderProvider]. The provider runs on a binder thread and stores the interface here;
 * the recording layer (wired in a LATER task) reads [service] from a background thread to drive the
 * command channel (`service.startRecording(...)` etc.).
 *
 * Mirrors Shizuku's `Shizuku.onBinderReceived` / `Shizuku.getBinder` static holder pattern and the
 * proven spike holder (persistserver/RecorderBinderDebugHolder.kt), upgraded to the production
 * [IRecorderService] interface with a [DeathRecipient] hook the provider links so the holder clears
 * when the daemon dies.
 */
object RecorderConnection {

    private const val TAG = "SCR:RecorderConn"

    /** The daemon-side interface, set by the provider on `sendBinder`, cleared on binder death. */
    @Volatile
    var service: IRecorderService? = null
        private set

    /** True once a binder has been received and is (was) alive. Cleared by [onBinderDied]. */
    val isConnected: Boolean
        get() = service != null

    /**
     * [IBinder.DeathRecipient] the provider links against the received binder so this holder clears
     * itself when the daemon process dies — callers then see a disconnected channel instead of a
     * [android.os.DeadObjectException] surprise on the next transaction.
     */
    val deathRecipient: IBinder.DeathRecipient = IBinder.DeathRecipient {
        AppLogger.w(TAG, "Daemon binder died; clearing RecorderConnection")
        onBinderDied()
    }

    /** Set by the provider after wrapping the received binder. */
    fun onBinderReceived(service: IRecorderService) {
        this.service = service
        AppLogger.i(TAG, "RecorderConnection received daemon binder")
    }

    /** Cleared when the daemon dies (via [deathRecipient]) so callers observe a stale channel. */
    fun onBinderDied() {
        this.service = null
    }
}
