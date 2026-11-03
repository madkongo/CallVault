/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.os.IBinder
import com.baba.callvault.utils.AppLogger
import com.baba.callvault.utils.SetupJournal

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

    private const val TAG = "CV:RecorderConn"

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
        // Setup has demonstrably worked, so the always-on setup journal has nothing left to learn and
        // stops here for good. A healthy phone therefore pays for it exactly once.
        SetupJournal.markSetupSucceeded()
        // The VoIP capture policy lives in the daemon process, so a relaunch loses it. Re-arm on every
        // fresh binder — it must be registered BEFORE a call starts, and there is no later chance.
        onDaemonReady?.invoke()
        runCatching { onConnected?.invoke() }.onFailure { AppLogger.w(TAG, "onConnected hook failed: ${it.message}") }
    }

    /**
     * Fired whenever a fresh binder arrives, for the keep-alive's "ready" notification. Without it the
     * notification learned of a recorder brought up by someone else (the app start, the update receiver)
     * only at its next 60 s check, and said "starting up" for a minute after an update while the recorder
     * was ready (OP9, 2026-11-02/03). Runs on the caller's thread, so it must not block.
     */
    @Volatile
    var onConnected: (() -> Unit)? = null

    /**
     * Invoked whenever a fresh daemon binder arrives, for state the daemon cannot keep across a
     * relaunch. Set once at app start; runs on a binder thread, so implementations must not block.
     */
    @Volatile
    var onDaemonReady: (() -> Unit)? = null

    /**
     * Optional hook fired (in addition to clearing [service]) the instant the daemon binder dies, so a
     * watcher (the keep-alive service) can relaunch the daemon RIGHT AWAY instead of waiting for its next
     * poll. Since linkToDeath only fires on genuine process death, this is an authoritative "daemon gone"
     * signal — the fastest possible recovery trigger.
     */
    @Volatile
    var onDeath: (() -> Unit)? = null

    /**
     * Cleared when the daemon dies (via [deathRecipient]) so callers observe a stale channel.
     *
     * **Only when the binder we are actually holding is the dead one.** One recipient is linked to every
     * binder we are handed, and a mode switch deliberately kills the previous host - so the OLD binder's
     * death arrives AFTER the new one has already been stored. Clearing unconditionally threw the live
     * recorder away: measured on the OP9, the new ADB daemon connected at 16:15:33.5, the killed Shizuku
     * service's death landed at 16:15:35.2, and the app was left believing it had no recorder while one
     * was running perfectly well.
     */
    fun onBinderDied() {
        val current = service?.asBinder()
        if (current != null && runCatching { current.isBinderAlive }.getOrDefault(false)) {
            AppLogger.i(TAG, "A previous recorder's binder died; the current one is alive - keeping it")
            return
        }
        this.service = null
        runCatching { onDeath?.invoke() }.onFailure { AppLogger.w(TAG, "onDeath hook failed: ${it.message}") }
    }

    /**
     * Drops the held binder even though it is still alive, for the one case where staying attached is
     * worse than detaching: a mode switch whose old recorder refused to die.
     *
     * [onBinderDied] deliberately keeps a live binder, which is right when the death is the *old*
     * host's. It is wrong when the live binder IS the old host: the switch then hands the new mode a
     * recorder belonging to the mode we just left, and every call recorded afterwards silently takes
     * the wrong capture path. Detaching costs at most one extra daemon start — and whichever recorder
     * comes up next clears the other, because `killStaleRecorders` runs on every start.
     */
    fun forceClear(reason: String) {
        if (service == null) return
        AppLogger.w(TAG, "Dropping a live recorder binder on purpose: $reason")
        this.service = null
        runCatching { onDeath?.invoke() }.onFailure { AppLogger.w(TAG, "onDeath hook failed: ${it.message}") }
    }
}
