/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Process
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.ModeCapability
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.utils.AppLogger
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Switches the ring listener ([VoipRingListenerService]) on while app-call recording is on, through
 * the recorder host's shell (`cmd notification allow_listener`), which both modes have: no screen to
 * visit, no new setting. Writing the `enabled_notification_listeners` secure setting from the app was
 * tried first and does nothing on Android 14: the setting changed and the system never bound the
 * listener (measured on the OP9, 2026-11-17).
 *
 * Only a switch we set is ever undone: it is removed again only when we approved it and app-call
 * recording is off; a listener the user enabled themselves is not touched, and not claimed as ours.
 * Every change is read back from the system rather than believed from the host's answer.
 *
 * Runs wherever "Record app calls" changes ([requestSync]): the toggle (Settings and the wizard), a mode
 * switch, a settings import, and once at start after the capability reconcile (review C19). It used to
 * run only at start, so turning app calls off left every app's notifications reaching the listener until
 * the process next started.
 */
object VoipRingListenerAccess {
    private const val TAG = "CV:RingAccess"
    private const val PREFS = "voip_ring_listener"
    private const val KEY_APPROVED_BY_US = "approved_by_us"

    /** The host may not be connected yet when the app starts: wait for it, but not forever. */
    private const val WAIT_STEP_MS = 5_000L
    private const val WAIT_MAX_MS = 3 * 60_000L

    /** One at a time, off the main thread: the host wait can last minutes. */
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "cv-ring-listener-access").apply { isDaemon = true }
    }

    /** Set by the first [requestSync]; every sync reads the preference fresh, so one context serves all. */
    @Volatile
    private var appContext: Context? = null

    private val syncer = CoalescingSyncer(worker) { superseded ->
        appContext?.let { sync(it, superseded) }
    }

    /** What one sync did, for the log and the tests. */
    enum class Outcome { NOTHING_TO_DO, GRANTED_BY_USER, APPROVED, REMOVED, NO_HOST, FAILED, SUPERSEDED }

    /**
     * At most one sync waiting to run, and a newer request supersedes one that is waiting for the host
     * (review of C19): a toggle storm while the host is down used to queue one wait of up to 3 minutes
     * per toggle. Every sync reads the switch when it runs, so the one left standing does what the
     * newest request wanted.
     */
    internal class CoalescingSyncer(
        private val executor: Executor,
        /** Runs one sync; its argument says whether a newer request has come in since it started. */
        private val run: (superseded: () -> Boolean) -> Outcome?,
    ) {
        private val generation = AtomicLong()
        private val queued = AtomicBoolean(false)

        fun request() {
            generation.incrementAndGet()
            if (!queued.compareAndSet(false, true)) return  // the queued one will read the latest state
            runCatching {
                executor.execute {
                    queued.set(false)
                    val mine = generation.get()
                    run { generation.get() != mine }
                }
            }.onFailure {
                queued.set(false)
                AppLogger.w(TAG, "Ring listener sync not queued: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    /** The phone and the host, behind a seam so the rules can be tested without either. */
    internal interface Port {
        fun wanted(): Boolean
        var approvedByUs: Boolean
        fun isGranted(): Boolean
        /**
         * Blocks until the recorder host is connected; true when it is. Gives up after the limit, or as
         * soon as [keepWaiting] says the wait no longer serves anything.
         */
        fun awaitHost(keepWaiting: () -> Boolean): Boolean
        /** The host's answer, for the log; null when the host went away. */
        fun setAccess(allow: Boolean): String?
    }

    /** Queues a [sync] off the main thread. Safe to call from anywhere, any number of times. */
    fun requestSync(context: Context) {
        appContext = context.applicationContext
        syncer.request()
    }

    /** [requestSync] when a mode reconcile turned app-call recording on or off. */
    fun syncIfAffected(context: Context, changed: Collection<ModeCapability>) {
        if (ModeCapability.VOIP_RECORDING in changed) requestSync(context)
    }

    /** Brings the system's approval in line with whether app-call recording is on. Blocking: call it off the main thread. */
    fun sync(context: Context, superseded: () -> Boolean = { false }): Outcome? =
        runCatching { syncWith(RealPort(context), superseded) }
            .onFailure { AppLogger.w(TAG, "Ring listener switch failed: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()

    /** The target the system should reach, or null when there is nothing for us to do. */
    private fun target(port: Port): Boolean? {
        val wanted = port.wanted()
        val granted = port.isGranted()
        return when {
            // The user approved it themselves: it stays theirs, and is never removed by us.
            wanted && granted && !port.approvedByUs -> null
            wanted -> true
            !port.approvedByUs -> null
            else -> false
        }
    }

    internal fun syncWith(port: Port, superseded: () -> Boolean = { false }): Outcome {
        if (target(port) == null) return idle(port)
        // Re-checked between wait steps, so a stale sync gives up instead of holding the queue for minutes.
        if (!port.awaitHost { !superseded() && target(port) != null }) {
            if (superseded()) {
                AppLogger.d(TAG, "Ring listener sync superseded by a newer request")
                return Outcome.SUPERSEDED
            }
            if (target(port) == null) return idle(port)
            AppLogger.i(TAG, "Ring listener not switched: the recorder host did not connect")
            return Outcome.NO_HOST
        }
        // Read again: the switch may have changed while we waited, and that change queued its own sync.
        val allow = target(port) ?: return idle(port)
        val answer = port.setAccess(allow)
        val nowGranted = port.isGranted()
        if (nowGranted != allow) {
            AppLogger.w(
                TAG,
                "Ring listener ${if (allow) "approval" else "removal"} did not take: the system still " +
                    "reports it ${if (nowGranted) "approved" else "not approved"}; host answered: $answer"
            )
            return Outcome.FAILED
        }
        port.approvedByUs = allow
        AppLogger.i(TAG, "Ring listener ${if (allow) "approved" else "removed"} through the host: $answer")
        return if (allow) Outcome.APPROVED else Outcome.REMOVED
    }

    private fun idle(port: Port): Outcome =
        if (port.wanted() && port.isGranted() && !port.approvedByUs) {
            AppLogger.i(TAG, "Ring listener already approved by the user; left as it is")
            Outcome.GRANTED_BY_USER
        } else {
            Outcome.NOTHING_TO_DO
        }

    private class RealPort(private val context: Context) : Port {
        private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private val component = ComponentName(context, VoipRingListenerService::class.java)
        private val userId = Process.myUserHandle().hashCode()

        override fun wanted() = AppPreferences(context).isVoipRecordingEnabled()

        override var approvedByUs: Boolean
            get() = prefs.getBoolean(KEY_APPROVED_BY_US, false)
            set(value) { prefs.edit().putBoolean(KEY_APPROVED_BY_US, value).apply() }

        override fun isGranted(): Boolean =
            context.getSystemService(NotificationManager::class.java)
                ?.isNotificationListenerAccessGranted(component) ?: false

        override fun awaitHost(keepWaiting: () -> Boolean): Boolean {
            var waited = 0L
            while (RecorderConnection.service == null && waited < WAIT_MAX_MS) {
                if (!keepWaiting()) return false
                Thread.sleep(WAIT_STEP_MS)
                waited += WAIT_STEP_MS
            }
            val connected = RecorderConnection.service != null
            if (!connected) AppLogger.i(TAG, "Recorder host not connected after ${waited / 1000}s")
            return connected
        }

        override fun setAccess(allow: Boolean): String? =
            RecorderConnection.service?.notificationListenerAccess(component.flattenToString(), allow, userId)
    }
}
