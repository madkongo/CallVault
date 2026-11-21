/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.app.Notification
import com.baba.callvault.system.diagnostics.CallEndLog
import com.baba.callvault.system.diagnostics.RecorderLogMarkers
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.baba.callvault.R
import com.baba.callvault.system.LocalizedContext
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.integrations.adb.UsbDebuggingEvents
import com.baba.callvault.services.call.ForegroundStartGuard
import com.baba.callvault.data.RecordingState
import com.baba.callvault.data.health.SetupHealthStore
import com.baba.callvault.integrations.adb.AdbShell
import com.baba.callvault.integrations.adb.UsbDebuggingRestorer
import com.baba.callvault.integrations.adb.ShellReadNeed
import com.baba.callvault.integrations.adb.UsbDebuggingShellRead
import com.baba.callvault.integrations.adb.TransportTimeline
import com.baba.callvault.integrations.adb.WifiState
import com.baba.callvault.integrations.adb.WirelessDebuggingOffCause
import com.baba.callvault.integrations.adb.DeveloperOptions
import com.baba.callvault.integrations.adb.UsbDefaultConfig
import com.baba.callvault.integrations.adb.UsbNotice
import com.baba.callvault.integrations.adb.WirelessDebuggingPolicy
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.server.RecorderBackend
import com.baba.callvault.server.ShizukuStatus
import com.baba.callvault.server.RecorderServerLauncher
import com.baba.callvault.system.health.SilentFailureNotifier
import com.baba.callvault.utils.AppLogger

/**
 * Persistent foreground service that keeps CallVault's privileged recorder daemon WARM — our
 * equivalent of what Shizuku's manager app does for its server: a durable foreground presence that
 * anchors the app process and actively re-warms the daemon the moment it dies, so a call is captured
 * instantly (over binder) instead of paying a cold-start that outlasts a short call.
 *
 * **Why a foreground service.** OnePlus/ColorOS reaps our detached shell-uid daemon on idle and on
 * screen transitions (it restarts adbd, which the daemon dies with). Holding a foreground service (a)
 * keeps our own process important, and (b) lets us notice the daemon died and relaunch it — ideally
 * BEFORE the next call. Recording itself flows over the daemon's binder, so once warm, calls record
 * even with Wi-Fi off (over the opt-in loopback transport).
 *
 * **Fast recovery.** A confirmed binder-death ([onDaemonDiedImmediate]) relaunches immediately, and the
 * notification flips to "ready" the instant the relaunch succeeds. A cheap 60s binder-liveness watchdog
 * is the backup for a death we somehow missed.
 *
 * **Modes.** Runs in every mode ([KeepAlivePolicy]). Standalone: the above, over our own ADB. Shizuku (app calls
 * on or off): the process anchor and status notice only; Shizuku keeps its own user service alive, and nothing
 * here touches our ADB (every ADB path is behind usesAdb(), re-read each time).
 */
class DaemonKeepAliveService : Service() {

    private val watchdogHandler = Handler(Looper.getMainLooper())
    /**
     * Null when it cannot be built. This service anchors carrier recording, so it must outlive its app-call detector:
     * on Android 11 the detector's constructor threw (2026-11-20), and the plain [VoipCallDetector.sync] below threw
     * again after the notification's guarded read, crash-looping the app on every start.
     */
    private val voipDetector: VoipCallDetector? by lazy {
        val what = "App-call detection"
        val alwaysOn: (Throwable) -> Unit = { failure ->
            UnavailablePartLog.recordOnce(what, failure) { line -> CallEndLog.record(applicationContext, line) }
        }
        buildOptionalPart(TAG, what, onFailure = alwaysOn) {
            VoipCallDetector(applicationContext).apply {
                // Reuse the permanent keep-alive notification to show that a VoIP call is being recorded,
                // rather than adding a second one. Without this the user has no sign it is working.
                onRecordingStateChanged = { updateNotification(RecorderConnection.isConnected) }
            }
        }
    }
    private var lastReady: Boolean? = null
    private val shellUsbReadGate = ShellUsbReadGate()

    /** Serialises and throttles relaunch attempts, and expires one that never came back. */
    private val rewarmGate = RewarmGate(stuckAfterMs = REWARM_STUCK_MS, throttleMs = REWARM_THROTTLE_MS)

    /**
     * Decides how to recover, and notices when recovery itself has stopped working.
     *
     * Process-wide rather than per-service-instance ([stuckRecovery]) so the failure streak survives the
     * service being restarted — the 2026-08-18 wedge outlived both an app restart and a reboot, and a
     * streak that resets on every restart would never reach the escalation it needs.
     */
    private val recoveryPolicy get() = stuckRecovery
    /** Consecutive "daemon down" readings — we only relaunch after a couple, to not churn on a blip. */
    private var downStreak = 0

    private val watchdog = object : Runnable {
        override fun run() {
            reconcileAdbWatchers("tick")
            val alive = isDaemonAlive()
            if (alive != (lastReady == true)) {
                lastReady = alive
                updateNotification(alive)
                AppLogger.d(TAG, "keep-alive: daemon alive=$alive")
                // The daemon has just come up, so the shell is free and nothing is racing us — the calm
                // moment to find out the USB mode if we never managed to. The recording path's own
                // attempt gets one un-retried shot while a call is starting, and on the device in issue
                // #22 that shot always failed, leaving the advice permanently unsayable.
                if (alive && usesAdb()) readUsbDefaultIfStillUnknown()
            } else if (!alive) {
                // Down and still down: what the notice should say can change without the daemon
                // changing — the failure streak trips, Wi-Fi drops or returns — so re-read it each tick
                // rather than leave "starting up" on screen for a recovery that cannot happen (#39).
                updateNotification(false)
            }
            if (alive) {
                downStreak = 0
                // A live daemon ends any failure streak, however it came back. The streak used to clear only
                // when OUR relaunch succeeded, so a recorder brought back by something else — a re-pair from
                // the "Pairing expired" card, or a call's own launch — left Home on "Recording is down" while
                // it recorded fine (OP9, 2026-09-29).
                recoveryPolicy.onAttemptSucceeded()
            } else {
                downStreak++
                // Debounce: act only after DOWN_STREAK_THRESHOLD consecutive down reads, so a transient
                // binder blip doesn't trigger a relaunch (which would kill+respawn a daemon that was fine).
                if (downStreak >= DOWN_STREAK_THRESHOLD) maybeRewarm()
            }
            runCatching { downAlert.onTick(alive) }
                .onFailure { AppLogger.w(TAG, "Recorder-down alert check failed: ${it.message}") }
            if (alive && usesAdb()) syncShellUsbRead()
            if (usesAdb()) timelineSnapshot(alive, "tick")
            healSharedNotice()
            // The recording folder, at most every few minutes and on its own thread (review C98): a folder deleted
            // between calls turns Home red and raises the alert before the next call, not after it.
            // Rides this existing tick (no timer of its own) and skips while a call is up (review 2026-11-20).
            com.baba.callvault.system.storage.RecordingFolderCheck.checkIfStale(
                this@DaemonKeepAliveService, "keep-alive tick", com.baba.callvault.system.storage.FolderWatchPlan.KEEP_ALIVE_GOOD_MAX_AGE_MS,
            )
            // R5: once a day, whether the phone's own settings can stop calls being recorded (one combined notice).
            // Rides this existing tick; cheap between checks, and logs its own failures without throwing.
            com.baba.callvault.system.health.RecordingRiskNotice.evaluate(
                this@DaemonKeepAliveService, com.baba.callvault.system.health.RecordingRiskNotice.Trigger.TICK,
            )
            watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    /** Raises and takes down the loud "Calls are not being recorded" alert (review R1); see [RecorderDownAlertRunner]. */
    private val downAlert = RecorderDownAlertRunner(
        isSwitchedOff = { AppPreferences(applicationContext).getRecordingState() == RecordingState.STOPPED },
        nowMs = { SystemClock.elapsedRealtime() },
        describeCause = {
            val notice = ReadinessNoticeText.current(this, ready = false)
            val shizuku = runCatching { AppPreferences(applicationContext).getPrivilegedMode().needsShizuku }.getOrDefault(false)
            val reasonRes = when {
                notice == ReadinessNotice.STARTING || notice == ReadinessNotice.STUCK ->
                    if (shizuku) R.string.notif_health_recorder_text_shizuku else R.string.notif_health_down_generic_text
                else -> ReadinessNoticeText.textNow(this, notice)
            }
            RecorderDownAlertRunner.Cause(notice.name, LocalizedContext.of(this).getString(reasonRes), shizuku)
        },
        post = { reason -> SilentFailureNotifier.warnRecorderDown(applicationContext, reason) },
        clear = { SilentFailureNotifier.clearRecorderUnavailable(applicationContext) },
        alwaysOnLog = { line -> com.baba.callvault.integrations.adb.UsbDebuggingEvents.record(applicationContext, line) },
    )

    private val healSharedNoticeRunnable = Runnable { healSharedNotice() }

    /**
     * Puts "Ready" back when what is actually posted under the shared id is a leftover from a call.
     *
     * A service's notification is posted asynchronously by system_server, while "Ready" goes straight to
     * NotificationManager — so a recording post can land after "Ready" and sit in the shade with nothing to
     * replace it: "Recording in progress" 30 s after the call, on a LAVA LXX508 (2026-09-26). This reads the
     * posted notification rather than trusting the order things were sent in. Runs a moment after each
     * release and on every watchdog tick.
     */
    private fun healSharedNotice() {
        val posted = runCatching {
            getSystemService(NotificationManager::class.java).activeNotifications
                .firstOrNull { it.id == NOTIF_ID }?.notification?.channelId
        }.getOrNull()
        if (!SharedStatusNotice.isStale(posted, CHANNEL_ID)) return
        AppLogger.i(TAG, "A leftover call notification ($posted) was still up after the call; putting \"Ready\" back")
        repostAsForeground(isDaemonAlive())
    }

    /**
     * Puts "Ready" back through this service's own startForeground rather than NotificationManager.notify.
     *
     * On a Pixel 10 Pro XL (Android 17, #73) notify() on the shared id did nothing: the heal ran twice and the
     * finished call's notification stayed until the user swiped it. startForeground updates the notification
     * this foreground service holds, which is the slot the leftover sits in. Only this heal uses it, so phones
     * where notify() works (the OP9, Android 14: three calls, never stale) never take this path.
     */
    private fun repostAsForeground(ready: Boolean) {
        if (ready) runCatching { SilentFailureNotifier.clearRecorderUnavailable(applicationContext) }
        if (SharedStatusNotice.isHeldByRecording) return
        ensureOwnChannel()
        runCatching {
            startForeground(NOTIF_ID, buildNotification(ready), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }.onFailure {
            AppLogger.w(TAG, "Could not re-post \"Ready\" as the foreground notice (${it.message}); trying notify")
            updateNotification(ready)
        }
    }

    /**
     * Re-posts the notice of a recorded call that still holds the shared id, after another one released it.
     * Through this service's startForeground for the same reason as [repostAsForeground] (Q49: on Android 17 a
     * notify() on a foreground service's id does not replace what is shown).
     */
    private fun repostHeldAsForeground(held: Notification) {
        // Never hand startForeground a notice on a missing channel (review N22): it crashes the app later.
        if (ForegroundNoticeChannel.postAsForeground(applicationContext, held, ::startOwnForeground)) return
        AppLogger.w(TAG, "Could not re-post the remaining recording notice as the foreground notice; trying notify")
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, held) }
            .onFailure { e -> AppLogger.w(TAG, "Could not re-post the remaining recording notice: ${e.message}") }
    }

    /** startForeground under the shared id, for a notice [ForegroundNoticeChannel] has checked. */
    private fun startOwnForeground(notification: Notification) {
        startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    /**
     * Creates the keep-alive's channel, or re-states it: idempotent, and it keeps whatever the user set on it. Run
     * again before each foreground post of "Ready", since this service lives for days and a channel can be deleted
     * meanwhile; a foreground notice on a missing channel crashes the app (review N22).
     */
    private fun ensureOwnChannel() {
        runCatching {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_readiness_channel), NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                },
            )
        }.onFailure { AppLogger.e(TAG, "Could not create the keep-alive notification channel: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /**
     * True only if the daemon process actually answers a binder ping — more reliable than
     * [RecorderConnection.isConnected] (`service != null`), which can stay stale-true if linkToDeath
     * missed, or read false on a transient. Matches the recording liveness watch's probe.
     */
    private fun isDaemonAlive(): Boolean = runCatching {
        RecorderConnection.service?.asBinder()?.pingBinder() == true
    }.getOrDefault(false)

    /**
     * Watches "USB debugging" so the daemon never silently loses its last way in.
     *
     * `adbd` only runs while USB debugging or Wireless debugging is enabled. When USB debugging is on,
     * CallVault switches Wireless debugging off — correctly, since `adbd` stays up. But if the user then
     * turns USB debugging off, BOTH are off: `adbd` stops, the daemon dies with it, and nothing notices
     * until a call is missed. That is not hypothetical — it cost a real outgoing call, where the cold
     * start took 18.3 s against a 15 s call and the recording never began. Shizuku's own wiki describes
     * users doing exactly this, because some banking apps demand USB debugging be off.
     *
     * So the policy is re-evaluated when the switches change, not only when the daemon launches.
     */
    private val usbDebuggingObserver = object : ContentObserver(watchdogHandler) {
        override fun onChange(selfChange: Boolean) {
            if (!usesAdb()) return // Shizuku mode: the switches are not ours to act on
            // Tri-state on purpose: a build that redacts the setting reports every change as "off", and
            // treating that as the user's doing wipes the observation window and manufactures an
            // unrecordable window that never existed. Only a proven off counts as off.
            // The recorder's read describes the switch as it was; drop it and read again in the background.
            UsbDebuggingShellRead.forget()
            refreshShellUsbRead()
            TransportTimeline.event(applicationContext, "USB debugging switch changed")
            val usb = AdbShell.usbDebuggingState(applicationContext)
            val wdOn = AdbShell.isWirelessDebuggingEnabled(applicationContext)
            // The notice names these switches, so it has to follow them immediately.
            updateNotification(isDaemonAlive())

            // The setting has just gone to 0 but adbd has not stopped yet, so the state still reads on — measured on
            // the OP9 (2026-11-04): this observer then did nothing at all. Look again once adbd has had time to stop.
            if (!usb.isOff && rawUsbDebuggingOff()) {
                Thread {
                    if (waitForUsbDebuggingOff()) watchdogHandler.post { onUsbDebuggingProvenOff() }
                }.apply { isDaemon = true; name = "cv-usb-off-settle" }.start()
                return
            }

            // Both directions matter, and both are immediate. Waiting for the next daemon launch to
            // re-evaluate means the user flips a switch and nothing visibly happens, which reads as
            // broken even when it eventually corrects itself.
            val reason = when {
                // USB debugging just went off. Stock Android stops adbd on that change whatever Wireless
                // debugging reads (init.usb.configfs.rc: sys.usb.config=none → stop adbd), and the daemon
                // dies with it. This is a user-caused unrecordable window opening RIGHT NOW, so the
                // observation window restarts immediately.
                usb.isOff -> return onUsbDebuggingProvenOff()
                // USB debugging now holds adbd up, so Wireless debugging is no longer needed. Dropping
                // it here is safe: with USB debugging enabled, toggling Wireless debugging does not
                // restart adbd (measured — its pid is unchanged), so the daemon is never at risk.
                wdOn -> "USB debugging switched on — Wireless debugging is no longer needed"
                else -> return
            }

            AppLogger.i(TAG, reason)
            applyTransportChange(usbOff = false)
        }
    }

    /**
     * USB debugging is proven off: stock Android stops adbd on that change whatever Wireless debugging reads
     * (init.usb.configfs.rc: sys.usb.config=none → stop adbd), and the daemon dies with it — an unrecordable
     * window opening right now, so the observation window restarts immediately.
     */
    private fun onUsbDebuggingProvenOff() {
        restartObservationWindow("USB debugging switched off")
        AppLogger.i(TAG, "USB debugging switched off — adbd stops with it; restarting it once the USB change settles")
        updateNotification(isDaemonAlive())
        applyTransportChange(usbOff = true)
    }

    private fun applyTransportChange(usbOff: Boolean) {
        Thread {
            runCatching {
                // Some phones switch USB debugging off by themselves (Honor's charge-only rule, Samsung).
                // Switching it back on is the only recovery that works away from Wi-Fi; the policy only does
                // it when the user cannot have done it by hand. See [UsbDebuggingRestorer].
                val restored = usbOff &&
                    UsbDebuggingRestorer.onUsbDebuggingOff(applicationContext, needsAdb = usesAdb())
                if (usbOff && !restored && usesAdb()) {
                    // The revival waits for the USB change to settle itself before touching Wireless
                    // debugging — a second wait here made recovery take 10.5 s on the OP9 instead of ~7.
                    AdbShell.reviveAdbdIfStopped(applicationContext, "USB debugging switched off", mayEnable = usesAdb())
                }
                // Re-runs the transport policy: with the daemon already connected this is just the
                // decision, no relaunch — and it is what switches Wireless debugging off.
                RecorderBackend.ensureRunning(applicationContext)
            }.onFailure { AppLogger.w(TAG, "Could not apply the transport change: ${it.message}") }
        }.apply { isDaemon = true; name = "cv-transport-change" }.start()
    }

    /** The raw setting reads 0. On a build that redacts it this is always true, so it only ever starts a look. */
    private fun rawUsbDebuggingOff(): Boolean =
        runCatching { Settings.Global.getInt(contentResolver, "adb_enabled", 1) == 0 }.getOrDefault(false)

    /** Waits up to [USB_OFF_SETTLE_MS] for USB debugging to be proven off (adbd stopped). Background thread only. */
    private fun waitForUsbDebuggingOff(): Boolean {
        val deadline = System.currentTimeMillis() + USB_OFF_SETTLE_MS
        while (System.currentTimeMillis() < deadline) {
            if (!rawUsbDebuggingOff()) return false
            if (AdbShell.usbDebuggingState(applicationContext).isOff) return true
            Thread.sleep(USB_OFF_POLL_MS)
        }
        return false
    }

    /**
     * Restarts the setup-health observation window the INSTANT readiness is lost, instead of waiting
     * for the user to next open the app ([com.baba.callvault.ui.viewmodels.HomeViewModel.refresh] is
     * otherwise the only place this window moves). Without this, a user who disables Wireless
     * debugging, misses a call, then re-enables it WITHOUT opening CallVault would never have the
     * unrecordable window recorded — and the missed call would read as a silent daemon failure
     * instead of a call that was never recordable in the first place.
     *
     * Wrapped so a failure here can never destabilise this service — health bookkeeping is strictly
     * secondary to keeping the daemon warm.
     */
    /**
     * Re-reads USB debugging through the recorder, off the main thread, and redraws the notice if the answer changed —
     * the only way to know it is off while Wireless debugging is also on (see [UsbDebuggingShellRead]).
     */
    private fun refreshShellUsbRead() {
        Thread { readShellUsb() }.apply { isDaemon = true; name = "cv-usb-shell-read" }.start()
    }

    /**
     * The keep-alive tick's (and a Wireless debugging change's) version of [refreshShellUsbRead]: asks the recorder only
     * where nothing cheaper answers ([ShellReadNeed.READ]); where R1 answers it records that proof instead, with a shell
     * read's freshness. Each read is a fork+exec in the recorder, which used to run every awake minute.
     */
    private fun syncShellUsbRead() {
        Thread {
            val startedIn = UsbDebuggingShellRead.generation()
            val step = shellUsbReadGate.decide(AdbShell.usbDebuggingShellReadNeed(applicationContext))
            step.log?.let { AppLogger.i(TAG, it) }
            when (step.need) {
                ShellReadNeed.READ -> readShellUsb()
                ShellReadNeed.R1_DECIDES -> UsbDebuggingShellRead.noteProvenOnByR1(startedIn)
                ShellReadNeed.SETTING_DECIDES -> UsbDebuggingShellRead.markNotNeeded()
            }
        }.apply { isDaemon = true; name = "cv-usb-shell-read" }.start()
    }

    /** Blocking: one read through the recorder; redraws the notice when the answer changed. */
    private fun readShellUsb() {
        val before = UsbDebuggingShellRead.current()
        val now = UsbDebuggingShellRead.refresh()
        if (now != null && now != before) {
            AppLogger.i(TAG, "USB debugging as the recorder reads it: ${if (now) "on" else "off"}")
            watchdogHandler.post { updateNotification(isDaemonAlive()) }
        }
    }

    /** One line of the always-on timeline, off the main thread (it reads several settings). */
    private fun timelineSnapshot(alive: Boolean, why: String) {
        Thread { runCatching { TransportTimeline.snapshot(applicationContext, alive, why) } }
            .apply { isDaemon = true; name = "cv-timeline" }.start()
    }

    /** A relaunch not attempted, written once per reason in a row so a long outage is one line, not hundreds. */
    @Volatile private var lastSkip: String? = null
    private fun timelineSkip(reason: String) {
        if (reason == lastSkip) return
        lastSkip = reason
        TransportTimeline.event(applicationContext, "not relaunching: $reason")
    }

    /** Screen and lock changes go on the timeline: the screen-off adbd restart is the suspect on these phones. */
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            if (!usesAdb()) return
            TransportTimeline.event(applicationContext, intent.action?.substringAfterLast('.') ?: "screen")
            timelineSnapshot(isDaemonAlive(), "screen")
            if (intent.action == android.content.Intent.ACTION_SCREEN_ON) {
                MicOpAutoHeal.onWayBackMayHaveAppeared(applicationContext, MicOpHealPolicy.Trigger.SCREEN_ON)
            }
        }
    }

    private fun restartObservationWindow(reason: String) {
        runCatching {
            SetupHealthStore(applicationContext).observationWindowStart(isReady = false, System.currentTimeMillis())
        }.onFailure { AppLogger.w(TAG, "Could not restart the observation window ($reason): ${it.message}") }
    }

    /**
     * Notes who owns the Wireless debugging switch, and does nothing else.
     *
     * This used to switch it back **off** when the user turned it on while USB debugging was already
     * holding `adbd` up — redundant from CallVault's point of view, and reported by mirror176 in #30 as
     * the app taking away a switch he had flipped to reach his phone from a PC. It flicked off within a
     * second, and the note explaining why told him to disable USB debugging instead, which costs him
     * the thing he wanted. "Wireless debugging only while it is needed" is a rule about CallVault's own
     * use of the switch; it was being applied to the switch itself.
     *
     * So the reaction is gone and the bookkeeping stays: a change we did not make means the switch is
     * now the user's, and [AdbShell.releaseWirelessDebugging] will leave it alone.
     * [AdbShell.didWeJustSetWirelessDebugging] is what tells our own writes from theirs.
     */
    private val wirelessDebuggingObserver = object : ContentObserver(watchdogHandler) {
        override fun onChange(selfChange: Boolean) {
            // Noted before the mode check, so a value seen while another mode ran is never left stale.
            val nowOn = AdbShell.isWirelessDebuggingEnabled(applicationContext)
            val previouslySeenOn = lastSeenWirelessDebuggingOn
            val msSinceLastOn = lastWirelessDebuggingOnAtMs.takeIf { it != 0L }?.let { SystemClock.elapsedRealtime() - it }
            lastSeenWirelessDebuggingOn = nowOn
            if (nowOn) lastWirelessDebuggingOnAtMs = SystemClock.elapsedRealtime()
            if (!usesAdb()) return // Shizuku mode: the switches are not ours to act on
            // Any switch of Wireless debugging gives adbd a new port, so a port search that failed may run again (#65).
            com.baba.callvault.integrations.adb.ConnectPortFinder.onWirelessDebuggingChanged()
            val on = AdbShell.isWirelessDebuggingEnabled(applicationContext)
            TransportTimeline.event(applicationContext, "Wireless debugging switch changed → ${if (on) "on" else "off"}")
            // A switch change: what was read or proven described the old switches. On, the app's own read can no longer
            // tell USB debugging, so the recorder's must be fresh now, not at the next tick.
            UsbDebuggingShellRead.forget()
            if (usesAdb()) syncShellUsbRead()
            val prefs = AppPreferences(applicationContext)
            if (on) {
                // On again, by anyone: nothing left to respect.
                prefs.setWirelessDebuggingTurnedOffByUser(false)
                MicOpAutoHeal.onWayBackMayHaveAppeared(applicationContext, MicOpHealPolicy.Trigger.WIRELESS_DEBUGGING_ON)
                val alive = isDaemonAlive()
                updateNotification(alive)
                // CallVault writes the switch on only from inside a launch or a revival that goes on to launch, so
                // a forced relaunch here would start a second one alongside it — and a relaunch that overruns drops
                // the ADB connection the first is using.
                if (AdbShell.didWeJustSetWirelessDebugging(enabled = true)) return
                // The user's switch. If recording was paused waiting for it, resume now rather than at the next tick.
                if (!alive) maybeRewarm(force = true)
                prefs.setWirelessDebuggingEnabledByUs(false)
                AppLogger.i(TAG, "Wireless debugging switched on by hand; it is the user\'s to manage")
                return
            }
            // Off. Only the user's own change may hold CallVault back — Android also writes it off, when it
            // refuses our write on an untrusted network and when Wi-Fi drops.
            when (
                WirelessDebuggingOffCause.of(
                    weJustTurnedItOff = AdbShell.didWeJustSetWirelessDebugging(enabled = false),
                    weJustTurnedItOn = AdbShell.didWeJustSetWirelessDebugging(enabled = true),
                    wifi = WifiState.of(applicationContext),
                    wifiJustLost = wifiLostRecently(),
                    weJustRestartedAdbd = AdbShell.didWeJustRestartAdbd(),
                    msSinceLastOn = msSinceLastOn,
                    lastSeenOn = previouslySeenOn,
                ).also {
                    AppLogger.i(
                        TAG,
                        "Wireless debugging off, verdict=$it (ms since last on=$msSinceLastOn, previous value on=$previouslySeenOn, " +
                            "wifi=${WifiState.of(applicationContext)}, wifi lost recently=${wifiLostRecently()})",
                    )
                }
            ) {
                WirelessDebuggingOffCause.OURS -> Unit
                WirelessDebuggingOffCause.ANDROID_REVERTED_AN_ON ->
                    AppLogger.i(TAG, "Wireless debugging went off within ${WirelessDebuggingOffCause.ANDROID_REVERT_WINDOW_MS} ms of being switched on; Android reverting it, not the user's choice")
                WirelessDebuggingOffCause.OFF_WHILE_ALREADY_OFF ->
                    AppLogger.i(TAG, "Wireless debugging off event while the last value seen was already off; an on-off pair, not a user action")
                WirelessDebuggingOffCause.AFTER_OUR_ADBD_RESTART ->
                    AppLogger.i(TAG, "Wireless debugging went off just after CallVault restarted adbd (another app, such as Shizuku, reacting); not the user's")
                WirelessDebuggingOffCause.ANDROID_NETWORK_CHANGED ->
                    AppLogger.i(TAG, "Wireless debugging went off as the phone left a Wi-Fi network; Android's doing, not the user's")
                WirelessDebuggingOffCause.ANDROID_REFUSED ->
                    AppLogger.i(TAG, "Android switched Wireless debugging off right after we turned it on (network not trusted)")
                WirelessDebuggingOffCause.ANDROID_NO_WIFI ->
                    AppLogger.i(TAG, "Wireless debugging went off with Wi-Fi; Android's doing, not the user's")
                WirelessDebuggingOffCause.USER -> {
                    prefs.setWirelessDebuggingEnabledByUs(false)
                    prefs.setWirelessDebuggingTurnedOffByUser(true)
                    AppLogger.i(
                        TAG,
                        "Wireless debugging switched off by hand; " +
                            if (prefs.isWirelessDebuggingEnforced()) "the override setting is on, so CallVault may switch it back on"
                            else "CallVault will leave it off",
                    )
                    // Android can write the switch off a moment before Wi-Fi is reported gone, which reads exactly
                    // like a tap. Measured on the OP9 (2026-09-15): Wi-Fi off, Android's "Disabling adbwifi" 0.18 s
                    // later, and this branch still saw Wi-Fi connected. Respect it at once, then look again once the
                    // network has settled. The cost: a user who switches it off and loses Wi-Fi within the recheck
                    // window is taken for Android — far rarer than every Wi-Fi drop being taken for the user.
                    watchdogHandler.removeCallbacks(recheckWirelessDebuggingOffCause)
                    watchdogHandler.postDelayed(recheckWirelessDebuggingOffCause, WD_OFF_RECHECK_MS)
                }
            }
            updateNotification(isDaemonAlive())
        }
    }

    /**
     * Takes back a "switched off by hand" that was really Wi-Fi going away.
     *
     * Left standing, it held recovery back for good: the keep-alive stands down while the user's switch is
     * respected, and nothing but the user switching it on again cleared it.
     */
    private val recheckWirelessDebuggingOffCause = Runnable {
        val prefs = AppPreferences(applicationContext)
        if (!prefs.wasWirelessDebuggingTurnedOffByUser()) return@Runnable
        // The Wi-Fi loss can be reported just after the switch went off; looked at again here.
        if (WifiState.of(applicationContext) != WifiState.NOT_CONNECTED && !wifiLostRecently()) return@Runnable
        AppLogger.i(TAG, "Wi-Fi went away with Wireless debugging; that was Android switching it off, not the user")
        prefs.setWirelessDebuggingTurnedOffByUser(false)
        updateNotification(isDaemonAlive())
    }

    /**
     * Approves each Wi-Fi the phone joins, while the recorder is alive to do it (opt-in, built-in mode).
     *
     * Android switches Wireless debugging off when Wi-Fi changes, and on a network it does not trust it asks
     * "Allow wireless debugging on this network?" the next time it is switched on. That next time is usually a
     * restart of the recorder, when no shell-uid process is left to approve it. See
     * [AdbShell.approveCurrentNetworkIfWanted].
     */
    private val wifiJoinCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            lastWifiLostAtMs = SystemClock.elapsedRealtime()
        }

        override fun onAvailable(network: Network) {
            if (!usesAdb()) return
            // Let Android finish its own "Wi-Fi changed" handling of the switch first.
            // Never sooner than 30 s after the last approval began (R16); a join inside that window is delayed, not dropped.
            watchdogHandler.removeCallbacks(approveJoinedWifi)
            watchdogHandler.postDelayed(
                approveJoinedWifi,
                WifiApprovalSpacing.delayMs(SystemClock.elapsedRealtime(), lastApprovalStartedAtMs, WIFI_JOIN_SETTLE_MS),
            )
            MicOpAutoHeal.onWayBackMayHaveAppeared(applicationContext, MicOpHealPolicy.Trigger.WIFI_JOINED)
        }
    }

    /** The last Wireless-debugging value the observer saw (null before the first), and when it last saw "on". */
    @Volatile
    private var lastSeenWirelessDebuggingOn: Boolean? = null

    @Volatile
    private var lastWirelessDebuggingOnAtMs = 0L

    /** When a Wi-Fi network was last lost; tells Android's "Wi-Fi changed" switch-off from the user's. */
    @Volatile
    private var lastWifiLostAtMs = 0L

    private fun wifiLostRecently(): Boolean =
        lastWifiLostAtMs != 0L && SystemClock.elapsedRealtime() - lastWifiLostAtMs < WIFI_LOST_WINDOW_MS

    /** When the last approval began (elapsed realtime), for [WifiApprovalSpacing]; null before the first. */
    @Volatile
    private var lastApprovalStartedAtMs: Long? = null

    private val approveJoinedWifi = Runnable {
        if (!usesAdb() || !AppPreferences(applicationContext).isWifiNetworkAutoApproveEnabled()) return@Runnable
        lastApprovalStartedAtMs = SystemClock.elapsedRealtime()
        Thread {
            if (!usesAdb()) return@Thread
            runCatching {
                if (AdbShell.approveCurrentNetworkIfWanted(applicationContext)) {
                    AppLogger.i(TAG, "Joined a Wi-Fi: approved it for Wireless debugging")
                }
            }.onFailure { AppLogger.w(TAG, "Could not approve the joined Wi-Fi: ${it.message}") }
        }.apply { isDaemon = true; name = "cv-wifi-approve" }.start()
    }

    /**
     * Watches the Developer options master toggle for the same reason as [usbDebuggingObserver]: with
     * it off, Wireless debugging cannot function and the daemon cannot run — a user-caused unrecordable
     * window that must be recorded the instant it opens, not discovered the next time the user happens
     * to open CallVault.
     */
    private val developerOptionsObserver = object : ContentObserver(watchdogHandler) {
        override fun onChange(selfChange: Boolean) {
            if (!usesAdb()) return
            if (!DeveloperOptions.isExplicitlyDisabled(applicationContext)) return
            restartObservationWindow("Developer options switched off")
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        AppPauseWatch.start(applicationContext)
        ensureOwnChannel()

        // The debugging switches are our embedded ADB's business only. This service outlives a mode switch, so the
        // watchers are reconciled with the mode now and again at every start and tick (P1a review).
        reconcileAdbWatchers("create")
    }

    /** True while the debugging-switch observers, screen receiver and Wi-Fi callback are registered. */
    private var watchersForAdb = false

    /** Registers or drops the ADB watchers so they match the mode as it is NOW; one always-on line per change. */
    private fun reconcileAdbWatchers(why: String) {
        when (KeepAlivePolicy.reconcileWatchers(watchersForAdb, usesAdb())) {
            KeepAlivePolicy.WatcherAction.NONE -> return
            KeepAlivePolicy.WatcherAction.REGISTER -> registerAdbWatchers()
            KeepAlivePolicy.WatcherAction.UNREGISTER -> unregisterAdbWatchers()
        }
        val line = "keep-alive: ADB watchers ${if (watchersForAdb) "registered" else if (usesAdb()) "registration FAILED, retrying at the next tick" else "dropped"} ($why); mode=${AppPreferences(applicationContext).getPrivilegedMode()}"
        AppLogger.i(TAG, line)
        // A registration that keeps failing is retried every tick; say so once, not once a minute, in the always-on file.
        val repeatedFailure = usesAdb() && !watchersForAdb && watcherFailureLogged
        if (usesAdb() && !watchersForAdb) watcherFailureLogged = true else if (watchersForAdb) watcherFailureLogged = false
        if (!repeatedFailure) runCatching { UsbDebuggingEvents.record(applicationContext, line) }
    }

    private var watcherFailureLogged = false

    private fun registerAdbWatchers() {
        // Per successful registration, so a failed one is retried at the next reconcile (the flag stays false until
        // all three have worked; registering an observer twice is harmless, and unregister tolerates a missing one).
        val observers = runCatching {
            contentResolver.registerContentObserver(Settings.Global.getUriFor("adb_enabled"), false, usbDebuggingObserver)
            contentResolver.registerContentObserver(Settings.Global.getUriFor("adb_wifi_enabled"), false, wirelessDebuggingObserver)
            contentResolver.registerContentObserver(Settings.Global.getUriFor("development_settings_enabled"), false, developerOptionsObserver)
        }.onFailure { AppLogger.w(TAG, "Could not watch the debugging switches: ${it.message}") }.isSuccess
        val screen = runCatching {
            registerReceiver(
                screenReceiver,
                android.content.IntentFilter().apply {
                    addAction(android.content.Intent.ACTION_SCREEN_OFF)
                    addAction(android.content.Intent.ACTION_SCREEN_ON)
                    addAction(android.content.Intent.ACTION_USER_PRESENT)
                },
            )
        }.onFailure { AppLogger.w(TAG, "Could not watch the screen: ${it.message}") }.isSuccess
        val wifi = runCatching {
            getSystemService(ConnectivityManager::class.java)?.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                wifiJoinCallback,
            )
        }.onFailure { AppLogger.w(TAG, "Could not watch for Wi-Fi joins: ${it.message}") }.isSuccess
        watchersForAdb = observers && screen && wifi
        // Partly registered is worse than not at all (a retry would double-register): undo, and retry next reconcile.
        if (!watchersForAdb) unregisterAdbWatchers()
    }

    private fun unregisterAdbWatchers() {
        watchersForAdb = false
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { contentResolver.unregisterContentObserver(usbDebuggingObserver) }
        runCatching { contentResolver.unregisterContentObserver(wirelessDebuggingObserver) }
        runCatching { contentResolver.unregisterContentObserver(developerOptionsObserver) }
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(wifiJoinCallback) }
        watchdogHandler.removeCallbacks(approveJoinedWifi)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // It runs in every mode now (P1a), so there is nothing to stand down for. The ADB daemon guarantee is kept
        // elsewhere: every ADB path here is gated on usesAdb(), which reads the mode each time, so a keep-alive
        // left over from standalone cannot relaunch our daemon in Shizuku mode (the OP9 bug).
        logDecisionIfChanged(applicationContext)
        reconcileAdbWatchers("start command")

        val startedForeground = runCatching {
            // While a call is recorded the shared notification shows the recording, with its controls;
            // starting here must not swap that for "Ready". See [SharedStatusNotice]. Unless that notice's channel
            // is missing: it would crash the app (review N22), so "Ready" it is, on a channel just made sure of.
            startForeground(
                NOTIF_ID,
                ForegroundNoticeChannel.noticeForStart(applicationContext, buildNotification(RecorderConnection.isConnected), ::ensureOwnChannel),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        }.onFailure { AppLogger.e(TAG, "keep-alive startForeground failed", it) }.isSuccess
        if (!startedForeground) {
            // Nothing else would start it again (review R3): queue a bounded retry before giving up.
            KeepAliveRetry.schedule(
                applicationContext,
                (intent?.getIntExtra(EXTRA_RETRY_ATTEMPT, 0) ?: 0) + 1,
                "startForeground failed",
            )
            stopSelf()
            return START_NOT_STICKY
        }
        // A recorded call has let go of the shared notification. Android does not cancel an id this service
        // still holds, so the recording's content would stay up after the call unless "Ready" goes back.
        // Set before the VoIP actions below return early: an instance started by one of them used to never
        // put "Ready" back. The second, delayed look catches a recording post that system_server delivers
        // after this one (LAVA LXX508) — see [healSharedNotice].
        // The app-call recording notice is posted through this service's startForeground, not notify() (C23, Q49).
        // A notice on a missing channel would crash the app later, uncatchably (review N22); false → notify().
        SharedStatusNotice.foregroundPoster = { notification ->
            ForegroundNoticeChannel.postAsForeground(applicationContext, notification, ::startOwnForeground)
        }
        SharedStatusNotice.onReleased = {
            watchdogHandler.post {
                // Another recorded call still holds the id (review C23): put ITS notice back, through startForeground
                // (Q49), not "Ready", or its Pause, Mark and Stop are gone for the rest of the call.
                val held = SharedStatusNotice.heldNotification
                if (held != null) repostHeldAsForeground(held) else updateNotification(isDaemonAlive())
            }
            watchdogHandler.removeCallbacks(healSharedNoticeRunnable)
            watchdogHandler.postDelayed(healSharedNoticeRunnable, SHARED_NOTICE_HEAL_DELAY_MS)
        }
        // The Record button on the "Ask me" prompt. Handled after startForeground above, so the
        // service is always in a legal foreground state before any work begins.
        if (intent?.action == ACTION_VOIP_STOP) {
            AppLogger.i(TAG, "Stop pressed on the VoIP recording notification")
            VoipRecordingCoordinator.onCallEnded(applicationContext)
            return START_STICKY
        }
        if (intent?.action == ACTION_VOIP_FLAG_MOMENT) {
            VoipRecordingCoordinator.markMoment(applicationContext)
            return START_STICKY
        }
        if (intent?.action == ACTION_VOIP_PAUSE || intent?.action == ACTION_VOIP_RESUME) {
            VoipRecordingCoordinator.setPaused(applicationContext, intent.action == ACTION_VOIP_PAUSE)
            return START_STICKY
        }
        if (intent?.action == ACTION_VOIP_RECORD_NOW) {
            voipDetector?.startNow()
        }
        if (intent?.action == ACTION_CARRIER_CALL_STARTED) {
            voipDetector?.abortForCarrierCall()
        }

        // Recover the INSTANT the daemon dies (binder linkToDeath) — don't wait for the next poll.
        // On a real incoming call this is what races (and hopefully beats) the call after a long idle.
        RecorderConnection.onDeath = { onDaemonDiedImmediate() }
        // And say "ready" the moment any recorder connects, whoever started it.
        RecorderConnection.onConnected = {
            // The phone's log around a USB-debugging-off event can only be read once the recorder is back.
            Thread {
                runCatching { UsbDebuggingRestorer.captureLogsIfPending(applicationContext) }
                    .onFailure { AppLogger.w(TAG, "Could not capture the USB-debugging log window: ${it.message}") }
            }.apply { isDaemon = true; name = "cv-usb-off-log" }.start()
            watchdogHandler.post {
                val alive = isDaemonAlive()
                if (alive != (lastReady == true)) {
                    lastReady = alive
                    updateNotification(alive)
                    AppLogger.d(TAG, "keep-alive: recorder connected; daemon alive=$alive")
                }
            }
        }
        // VoIP detection lives here rather than in its own component: this service is already a
        // permanent foreground presence, so watching for VoIP calls costs no extra process and no
        // second notification, and VoIP gets exactly the same lifetime as carrier recording.
        voipDetector?.sync()

        // Kick the watchdog now (first tick warms the daemon immediately if it's cold).
        lastReady = null
        watchdogHandler.removeCallbacks(watchdog)
        watchdogHandler.post(watchdog)
        // START_STICKY: if the OS kills us under pressure, restart so the daemon anchor comes back.
        return START_STICKY
    }

    /**
     * Called from [RecorderConnection.onDeath] the moment the daemon binder dies — an authoritative
     * "process gone" signal (linkToDeath only fires on real death). Relaunch immediately, skipping the
     * poll interval AND the debounce. Posted to the handler so we're off the binder thread.
     */
    private fun onDaemonDiedImmediate() {
        watchdogHandler.post {
            AppLogger.i(TAG, "keep-alive: binder-death signal — ${RecorderLogMarkers.RELAUNCH} immediately")
            if (usesAdb()) {
                TransportTimeline.event(applicationContext, "recorder died")
                timelineSnapshot(false, "death")
            }
            lastReady = false
            updateNotification(false)
            downStreak = DOWN_STREAK_THRESHOLD // death confirmed — no debounce needed
            // Confirmed death (linkToDeath) is authoritative — relaunch NOW, bypassing the throttle.
            // The throttle exists to avoid HAMMERING a failed relaunch, NOT to delay a genuine recovery:
            // on OnePlus the daemon dies on every screen transition, so a death shortly after a prior
            // relaunch was being throttled — the cause of a long "starting up" window a call would race.
            maybeRewarm(force = true)
        }
    }

    /**
     * Reads the Default USB Configuration on a background thread, if it was never successfully read.
     *
     * No-op once a value is known, so this is one read per install in the normal case. The notification
     * is refreshed afterwards because the answer may be what decides whether it carries a warning.
     */
    private fun readUsbDefaultIfStillUnknown() {
        Thread {
            val mode = runCatching { UsbDefaultConfig.readIfUnknown(applicationContext) }
                .onFailure { AppLogger.d(TAG, "keep-alive: USB-default read failed: ${it.message}") }
                .getOrNull() ?: return@Thread
            AppLogger.i(TAG, "keep-alive: Default USB Configuration resolved to $mode")
            watchdogHandler.post { updateNotification(isDaemonAlive()) }
        }.apply { isDaemon = true; name = "cv-usbdefault-calm" }.start()
    }

    /**
     * Relaunch the daemon if it's down. [force] (a confirmed binder-death) bypasses the throttle so a
     * genuine recovery isn't delayed; the un-forced watchdog path still throttles to avoid hammering a
     * relaunch that keeps failing. Loopback records off-Wi-Fi, so only the non-offline (Wireless
     * Debugging) relaunch path requires Wi-Fi. When the daemon is already connected the [watchdog] never
     * calls this, so an active recording is implicitly skipped and never churned.
     */
    private fun maybeRewarm(force: Boolean = false) {
        if (!usesAdb()) {
            rebindShizuku(force)
            return
        }
        // A recording on the local ADB fallback rides the very connection a relaunch drops or reconfigures (review C89).
        if (LocalFallbackLease.shared.expireIfStale()) {
            val line = "keep-alive: the local-fallback lease was held over ${LocalFallbackLease.MAX_HOLD_MS / 60_000} min; treating it as a missed release"
            AppLogger.w(TAG, line)
            runCatching { UsbDebuggingEvents.record(applicationContext, line) }
        }
        if (LocalFallbackLease.shared.active) {
            timelineSkip("a recording on the local fallback is using the ADB connection")
            return
        }
        val offline = runCatching { AppPreferences(this).isOfflineRecordingEnabled() }.getOrDefault(false)
        // Every network, not the default one: a Wi-Fi without internet while mobile data is up is still Wi-Fi (review C34).
        if (!RewarmDecisions.wifiAllowsRelaunch(offline, WifiState.of(this))) {
            timelineSkip("no Wi-Fi and off-Wi-Fi recording is off")
            return // the WD relaunch path needs Wi-Fi; loopback doesn't
        }
        // Nothing to try while recovery is known to be impossible — the user turned the only way in off, or
        // USB debugging is off with no Wi-Fi. Seen on the emulator: the launcher retried every 12 s, logging a
        // refusal each time, for as long as the switch stayed off. The switch observer comes back here the
        // moment either switch changes, and the watchdog still ticks for Wi-Fi returning.
        val known = ReadinessNoticeText.current(this, ready = false)
        if (known == ReadinessNotice.WD_OFF_BY_USER || known == ReadinessNotice.NEEDS_WIFI) {
            timelineSkip("recovery impossible: $known")
            updateNotification(false)
            return
        }
        // Checked AFTER the Wi-Fi guard so a relaunch we never attempt does not consume the throttle.
        if (!rewarmGate.tryEnter(SystemClock.elapsedRealtime(), force)) return
        Thread {
            AppLogger.i(TAG, "keep-alive: daemon down — ${RecorderLogMarkers.RELAUNCH} (force=$force offline=$offline)")
            // Restore a usable endpoint FIRST, explicitly. The connect path only rediscovers a missing
            // one after ~12 s of failing loopback probes, so doing it here is the difference between
            // recovering in seconds and sitting on "starting up" indefinitely — which is what happened
            // on a OnePlus 12 after USB debugging was switched off: twenty minutes down, and it only
            // came back when the app was opened by hand.
            //
            // What counts as "usable" is [DaemonRecoveryPolicy]'s job, and getting it wrong cost a
            // silent multi-hour outage on 2026-08-18 — see that class. In short: USB debugging being
            // enabled is NOT a transport this app can dial, and an attempt that keeps timing out must
            // be escalated rather than repeated.
            // A switch that reads on is not proof adbd is up: turning USB debugging off stops it while Wireless
            // debugging still reads on, and every attempt below would then dial nothing. Catches the case
            // the switch observer missed — the service was not running when it happened, or it rebooted.
            if (!adbStillAllowed("revive adbd")) { rewarmGate.leave(); return@Thread }
            runCatching { AdbShell.reviveAdbdIfStopped(applicationContext, "keep-alive relaunch", mayEnable = usesAdb()) }
                .onFailure { AppLogger.w(TAG, "keep-alive: could not check adbd: ${it.message}") }
            when (
                recoveryPolicy.nextStep(
                    wirelessDebuggingEnabled = AdbShell.isWirelessDebuggingEnabled(applicationContext),
                    usbDebuggingEnabled = AdbShell.isUsbDebuggingEnabled(applicationContext),
                    loopbackArmed = AdbShell.isLoopbackArmed(applicationContext),
                )
            ) {
                RecoveryStep.RESTORE_WIRELESS_DEBUGGING -> {
                    AppLogger.w(TAG, "keep-alive: no TCP endpoint to dial — switching Wireless debugging back on")
                    // Let the switch observer attribute the change first. Measured on the OP9 without this:
                    // the user tapped Wireless debugging off and this switched it back on 50 ms later, before
                    // anything had recorded that it was the user's tap. The gate reads that record.
                    Thread.sleep(RESTORE_SETTLE_MS)
                    if (!adbStillAllowed("re-enable Wireless debugging")) { rewarmGate.leave(); return@Thread }
                    runCatching { AdbShell.enableWirelessDebugging(applicationContext) }
                        .onFailure { AppLogger.w(TAG, "keep-alive: could not re-enable Wireless debugging: ${it.message}") }
                }

                RecoveryStep.REBUILD_CONNECTION -> {
                    // The endpoint looks fine and attempts still time out, so stop trusting it: drop the
                    // connection outright, and make sure Wireless debugging is on so there is a second
                    // endpoint to reach even if the loopback one is the wedged half. Manually enabling
                    // Wireless debugging is exactly what un-wedged the 2026-08-18 device.
                    AppLogger.w(TAG, "keep-alive: relaunch keeps timing out — ${RecorderLogMarkers.ADB_REBUILD}")
                    if (!adbStillAllowed("rebuild the ADB connection")) { rewarmGate.leave(); return@Thread }
                    runCatching { AdbShell.dropConnection(applicationContext) }
                        .onFailure { AppLogger.w(TAG, "keep-alive: could not drop the ADB connection: ${it.message}") }
                    if (!adbStillAllowed("re-enable Wireless debugging")) { rewarmGate.leave(); return@Thread }
                    runCatching { AdbShell.enableWirelessDebugging(applicationContext) }
                        .onFailure { AppLogger.w(TAG, "keep-alive: could not re-enable Wireless debugging: ${it.message}") }
                }

                RecoveryStep.CONNECT -> Unit
            }
            val startedAt = SystemClock.elapsedRealtime()
            val outcome = try {
                launchDaemonBounded()
            } finally {
                // ALWAYS release, even if the bounded launch threw. The gate expiring is the safety net;
                // this is the normal path, and leaving it to the net would cost a whole stuck window.
                rewarmGate.leave()
            }
            val ok = outcome == LaunchOutcome.CONNECTED
            TransportTimeline.event(
                applicationContext,
                "relaunch (force=$force offline=$offline) → $outcome in ${SystemClock.elapsedRealtime() - startedAt} ms",
            )
            timelineSnapshot(ok, "relaunch")
            // Feed the outcome back so a run of failures escalates instead of repeating unchanged. This comes
            // BEFORE the rescue below on purpose: on 2026-09-14 the rescue itself blocked, this line was never
            // reached, and six hours of relaunches never once escalated or told the user.
            if (ok) recoveryPolicy.onAttemptSucceeded() else recoveryPolicy.onAttemptFailed()
            if (outcome == LaunchOutcome.TIMED_OUT && adbStillAllowed("drop the timed-out ADB connection")) AdbShell.dropConnection(applicationContext)
            // Flip the notification to "ready" the INSTANT the relaunch succeeds — don't wait for the next
            // 60s watchdog tick. Without this the daemon reconnects in seconds but the user would still see
            // "starting up" for up to a minute (a perceived-but-false slow recovery).
            if (ok) watchdogHandler.post { lastReady = true; updateNotification(true) }
        }.apply { isDaemon = true; name = "cv-keepalive-rewarm" }.start()
    }

    /**
     * Runs [RecorderServerLauncher.ensureServerRunning] under a hard time bound, so this service can
     * never be left waiting on it forever.
     *
     * `ensureServerRunning` takes `AdbShell.heavyOperationLock` and then `AdbShell.ensureConnected`,
     * which is unbounded: on a `CLOSE_WAIT` socket it blocks with no timeout. On 2026-07-30 that cost a
     * device ~21 hours of silently missed recordings — one hung call latched the old `rewarming` flag
     * and the watchdog never relaunched again.
     *
     * On timeout the worker is abandoned (it is a daemon thread), its stack goes to the log, and the caller
     * — after recording the failure — drops the ADB connection, which unblocks its read so it can die and
     * release the lock. Without that drop the abandoned thread keeps
     * `heavyOperationLock` and every later attempt piles up behind it — bounded, but never succeeding.
     */
    private fun launchDaemonBounded(): LaunchOutcome {
        val ok = java.util.concurrent.atomic.AtomicBoolean(false)
        val worker = Thread {
            ok.set(
                runCatching { RecorderBackend.ensureRunning(applicationContext) }
                    .onFailure { AppLogger.w(TAG, "keep-alive relaunch failed: ${it.message}") }
                    .getOrDefault(false)
            )
        }.apply { isDaemon = true; name = "cv-keepalive-launch" }
        worker.start()
        runCatching { worker.join(LAUNCH_BUDGET_MS) }
        if (worker.isAlive) {
            AppLogger.w(
                TAG,
                "keep-alive: relaunch still blocked after ${LAUNCH_BUDGET_MS}ms — abandoning it and " +
                    "dropping the ADB connection so it can unwind and free the lock. It is parked at:\n" +
                    worker.stackTrace.take(STACK_FRAMES_LOGGED).joinToString("\n") { "    at $it" },
            )
            return LaunchOutcome.TIMED_OUT
        }
        return if (ok.get()) LaunchOutcome.CONNECTED else LaunchOutcome.FAILED
    }

    /**
     * Shizuku mode's whole recovery: ask Shizuku for the user service again. No Wi-Fi check (Shizuku
     * needs none once running), no adbd, no Wireless debugging, and no ADB connection to drop — none of
     * that is ours to touch in this mode. A Shizuku that is not running simply fails, and the
     * "start Shizuku" notice already covers that.
     */
    private fun rebindShizuku(force: Boolean) {
        // Shizuku not running (after a reboot, before the user starts it): asking again every 20 s only fills the log
        // and spends battery. The status notice and R1 alert already say so; resume the moment it is READY.
        val status = runCatching { RecorderBackend.shizukuStatus(applicationContext) }.getOrNull()
        if (status != null && status != ShizukuStatus.READY) {
            if (status != lastShizukuSkipStatus) {
                lastShizukuSkipStatus = status
                AppLogger.i(TAG, "keep-alive (Shizuku): not asking for the recorder while Shizuku is $status")
            }
            return
        }
        lastShizukuSkipStatus = null
        if (!rewarmGate.tryEnter(SystemClock.elapsedRealtime(), force)) return
        Thread {
            AppLogger.i(TAG, "keep-alive (Shizuku): recorder down — asking Shizuku for it again (force=$force)")
            val outcome = try { launchDaemonBounded() } finally { rewarmGate.leave() }
            if (outcome == LaunchOutcome.CONNECTED) watchdogHandler.post { lastReady = true; updateNotification(true) }
        }.apply { isDaemon = true; name = "cv-keepalive-rebind" }.start()
    }

    /** Re-checks the mode right before an ADB action of a relaunch that began in standalone; logs an abort once, always-on. */
    private fun adbStillAllowed(step: String): Boolean {
        if (usesAdb()) return true
        val line = "keep-alive: relaunch aborted before '$step': the mode changed to ${AppPreferences(applicationContext).getPrivilegedMode()}"
        AppLogger.i(TAG, line)
        runCatching { UsbDebuggingEvents.record(applicationContext, line) }
        return false
    }

    private var lastShizukuSkipStatus: ShizukuStatus? = null

    private fun usesAdb(): Boolean =
        KeepAlivePolicy.mayUseAdb(AppPreferences(applicationContext).getPrivilegedMode())

    /** How a bounded relaunch ended. A timeout also needs its ADB connection dropped. */
    private enum class LaunchOutcome { CONNECTED, FAILED, TIMED_OUT }

    /** Tells the user CallVault undid their Wireless-debugging change, and why. Dismissible, silent. */

    private fun updateNotification(ready: Boolean) {
        // The boot path posts "CallVault cannot record right now" and, until 2026-09-19, was also the only
        // thing that ever took it down again — on its own success branch. So a boot that failed left the
        // warning standing for the rest of the uptime, including after the recorder came back, telling a
        // user their phone was broken while it was recording. Cleared here because this is the one place
        // that knows the recorder is up, whoever brought it up.
        if (ready) runCatching { SilentFailureNotifier.clearRecorderUnavailable(applicationContext) }
        // A recorded call owns the notification — posting now would replace Pause, Mark and Stop, the only
        // controls during a call, with "Ready to record calls". The release puts this back afterwards.
        if (SharedStatusNotice.isHeldByRecording) return
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            val next = buildNotification(ready)
            // The same notice, still posted exactly as it would be: posting again only wakes system UI and every
            // notification listener (review R11). Anything else under the id (a recording's, a stale one, none because
            // the user swiped it) is posted over. A read that fails posts, as before.
            val posted = runCatching { manager.activeNotifications.firstOrNull { it.id == NOTIF_ID }?.notification }.getOrNull()
            if (!NoticeKey.shouldPost(posted, next)) return@runCatching
            manager.notify(NOTIF_ID, next)
        }
    }

    private fun buildNotification(ready: Boolean): Notification {
        val voipRecording = voipDetector?.isRecording == true
        // Text from the app's language, not only the phone's (BabApps item 123); this service lives for days.
        val loc = LocalizedContext.of(this)
        val notice = ReadinessNoticeText.current(this, ready)
        val baseText = when {
            voipRecording -> loc.getString(R.string.notif_voip_recording_text)
            else -> loc.getString(ReadinessNoticeText.textNow(this, notice))
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(
                when {
                    voipRecording -> loc.getString(R.string.notif_voip_recording_title)
                    else -> loc.getString(ReadinessNoticeText.title(notice))
                },
            )
            .setContentText(baseText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)

        // The Default USB Configuration decides whether a screen transition restarts adbd and kills the
        // daemon, so it is surfaced here — NOT gated on readiness. It used to be, and that made it
        // useless exactly when it mattered: the data mode kills the daemon, the dead daemon reads as
        // not-ready, and not-ready hid the warning. See [UsbDefaultConfig.noticeFor].
        // Both ADB lines (this USB tip and "Wireless debugging has to stay on" below) are about our own ADB
        // daemon. In Shizuku mode Shizuku owns the transport and we never touch the switches (#71: a Shizuku
        // user was told CallVault would switch Wireless debugging off), so neither is shown there.
        val adbAdvice = usesAdb()
        val usbNotice = if (adbAdvice) UsbDefaultConfig.noticeFor(UsbDefaultConfig.cached(this), ready) else UsbNotice.NONE
        // Except when recording is down for a named reason: then that reason is the only thing worth the
        // collapsed line. Measured on the emulator — "USB debugging is off and there's no Wi-Fi" was hidden
        // behind the screen-lock tip, which is about a recording that cannot happen anyway.
        val noticeOwnsLine = notice != ReadinessNotice.READY && notice != ReadinessNotice.STARTING
        // The reason is the whole message; don't let the collapsed line cut it off (it read "Turn it ba…").
        if (noticeOwnsLine) builder.setStyle(NotificationCompat.BigTextStyle().bigText(baseText))
        // USB debugging went off and was not switched back on by itself (the screen was on, or the phone kept
        // switching it off). One tap turns it back on — and on Honor/Huawei also the charge-only ADB option
        // whose absence is what switches it off.
        if (UsbDebuggingActionReceiver.offers(this, notice)) {
            builder.addAction(
                0,
                loc.getString(R.string.notif_action_turn_usb_on),
                UsbDebuggingActionReceiver.pendingIntent(this),
            )
        }
        if (notice == ReadinessNotice.WD_OFF_BY_USER) {
            builder.addAction(
                0,
                loc.getString(R.string.notif_action_turn_wd_on),
                WirelessDebuggingActionReceiver.pendingIntent(this),
            )
        }
        if (usbNotice != UsbNotice.NONE && !noticeOwnsLine) {
            val warning = loc.getString(
                when (usbNotice) {
                    UsbNotice.DATA_MODE_RISK -> R.string.notif_usb_lock_warning
                    else -> R.string.notif_usb_check_unavailable
                },
            )
            builder.setContentText(warning)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$baseText\n$warning"))
            return builder.build()
        }

        // Wireless debugging has to stay on when it is adbd's ONLY transport, because switching it off
        // would stop adbd and take the daemon with it. Say so rather than leaving the user to notice that
        // a debugging switch they did not turn on is staying on — and name the two settings that free it.
        if (adbAdvice && notice == ReadinessNotice.READY && WirelessDebuggingPolicy.mustKeepWirelessDebugging(AdbShell.wirelessDebuggingPlan(this))) {
            val notice = loc.getString(R.string.notif_wd_required)
            builder.setContentText(notice)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$baseText\n$notice"))
        }
        return builder.build()
    }

    override fun onDestroy() {
        isRunning = false
        // Nobody else clears the alert once this service is gone (review R1 follow-up).
        runCatching { downAlert.onServiceStopping("service destroyed") }
        AppPauseWatch.stop()
        unregisterAdbWatchers()
        runCatching { voipDetector?.stop() }
        watchdogHandler.removeCallbacks(watchdog)
        watchdogHandler.removeCallbacks(healSharedNoticeRunnable)
        RecorderConnection.onDeath = null
        RecorderConnection.onConnected = null
        SharedStatusNotice.onReleased = null
        SharedStatusNotice.foregroundPoster = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** How long adbd may take to stop after USB debugging goes off before the off is believed. */
        private const val USB_OFF_SETTLE_MS = 5_000L
        private const val USB_OFF_POLL_MS = 200L
        private const val TAG = RecorderLogMarkers.KEEP_ALIVE_TAG
        internal const val CHANNEL_ID = "recorder_keepalive"

        /**
         * Sent by the "Ask me" prompt's Record button. Handled here because this service owns the
         * [VoipCallDetector] instance that knows a call is up — routing it anywhere else would mean
         * a second component tracking the same call state.
         */
        const val ACTION_VOIP_RECORD_NOW = "com.baba.callvault.VOIP_RECORD_NOW"

        /** Stops an app-call recording from its ongoing notification. */
        const val ACTION_VOIP_STOP = "com.baba.callvault.VOIP_STOP"

        /** Marks the current moment in an app-call recording, from its ongoing notification. */
        const val ACTION_VOIP_FLAG_MOMENT = "com.baba.callvault.VOIP_FLAG_MOMENT"

        /** Pauses an app-call recording from its ongoing notification. */
        const val ACTION_VOIP_PAUSE = "com.baba.callvault.VOIP_PAUSE"

        /** Resumes a paused app-call recording. */
        const val ACTION_VOIP_RESUME = "com.baba.callvault.VOIP_RESUME"

        /**
         * Sent by [com.baba.callvault.services.call.CallSessionManager] when a carrier call is
         * answered. Handled here for the same reason as above: this service owns the detector that
         * knows whether an app-call recording is running. See [VoipTelephonyGate].
         */
        const val ACTION_CARRIER_CALL_STARTED = "com.baba.callvault.CARRIER_CALL_STARTED"

        /**
         * Recovery state for the whole process.
         *
         * Deliberately not a field of the service. The 2026-08-18 wedge survived the app being
         * force-quit and a full reboot, and a failure streak that reset whenever the service restarted
         * would never reach the escalation that ends it. Held here so the streak outlives the instance,
         * and so the UI can ask whether recovery is stuck without binding to the service.
         */
        val stuckRecovery = DaemonRecoveryPolicy()

        /**
         * Whether daemon recovery has been failing repeatedly.
         *
         * Home reads this to say recording is down and why. The original outage was invisible: the app
         * showed nothing at all while recording was dead for hours.
         */
        val isRecoveryStuck: Boolean get() = stuckRecovery.isStuck

        /** True between onCreate and onDestroy of the live keep-alive service (a probe, for review N26 / Q53). */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** Low-importance channel for one-off explanations, kept apart from the permanent status note. */
        private const val NOTIF_ID = SharedStatusNotice.ID

        /** How long after a "switched off by hand" Wi-Fi is looked at again, in case its loss was the real cause. */
        private const val WD_OFF_RECHECK_MS = 3_000L

        /** After joining a Wi-Fi, before approving it: Android writes its own "Wi-Fi changed" off first. */
        private const val WIFI_JOIN_SETTLE_MS = 4_000L

        /** A switch-off this close to a Wi-Fi loss (either order, read again at the recheck) is Android's. */
        private const val WIFI_LOST_WINDOW_MS = 6_000L

        /** Frames of a stuck relaunch's stack written to the log — enough to name the lock it waits on. */
        private const val STACK_FRAMES_LOGGED = 14

        /** How often the watchdog checks the daemon is alive. Cheap (a binder ping). */
        private const val WATCHDOG_INTERVAL_MS = 60_000L

        /** How long after a release the posted notification is checked again. Covers a late system_server post. */
        private const val SHARED_NOTICE_HEAL_DELAY_MS = 1_500L

        /** How long a restore waits for the switch observer to say who turned Wireless debugging off. */
        private const val RESTORE_SETTLE_MS = 1_000L

        /**
         * Minimum gap between UN-FORCED (watchdog) relaunch attempts, so a persistently-failing relaunch
         * isn't hammered. A confirmed binder-death relaunches immediately via `maybeRewarm(force = true)`
         * and ignores this. Kept modest (was 90s — which delayed real recoveries when the daemon died
         * shortly after a prior relaunch, as it does on every OnePlus screen transition).
         */
        private const val REWARM_THROTTLE_MS = 20_000L

        /**
         * Hard bound on one relaunch attempt. `ensureServerRunning` budgets 24 s across three attempts,
         * so this leaves comfortable headroom for a slow-but-healthy launch while still capping a wedged
         * one. Exceeding it means the ADB connection is half-dead, not that the daemon is slow.
         */
        private const val LAUNCH_BUDGET_MS = 45_000L

        /**
         * Age past which an in-flight relaunch is presumed abandoned and may be superseded. Must exceed
         * [LAUNCH_BUDGET_MS] — otherwise a launch that is merely slow gets a second one racing it — and
         * is the backstop for a bounded attempt that somehow still fails to return. See [RewarmGate].
         */
        private const val REWARM_STUCK_MS = 90_000L

        /** Consecutive down reads before relaunching — debounces a transient binder blip into no action. */
        private const val DOWN_STREAK_THRESHOLD = 2

        private val decisionLog = KeepAliveDecisionLog()

        /** Writes why the keep-alive runs (mode, app-calls flag, role) to the always-on log, once per change. */
        private fun logDecisionIfChanged(context: Context) {
            runCatching {
                val prefs = AppPreferences(context)
                val line = decisionLog.lineIfChanged(
                    KeepAlivePolicy.decision(prefs.getPrivilegedMode(), prefs.isVoipRecordingEnabled()),
                ) ?: return
                AppLogger.i(TAG, line)
                UsbDebuggingEvents.record(context, line)
            }.onFailure { AppLogger.w(TAG, "Could not log the keep-alive decision: ${it.message}") }
        }

        /** Which retry started this run (review R3); absent on a normal start. */
        internal const val EXTRA_RETRY_ATTEMPT = "keep_alive_retry_attempt"

        /** Start (or no-op if already running) the persistent keep-alive anchor. Safe to call repeatedly. */
        fun start(context: Context) {
            // A refused start used to leave the keep-alive down with nothing to try again (review R3).
            if (!startOnce(context, retryAttempt = 0)) KeepAliveRetry.schedule(context, 1, "start refused")
        }

        /** One start attempt. False only when Android refused the foreground start; a switched-off recorder counts as done. */
        internal fun startOnce(context: Context, retryAttempt: Int): Boolean {
            // The user turned recording off — honour it here so EVERY caller (boot, app launch,
            // post-update, Settings) respects it without each having to check. STOPPED survives a reboot:
            // the recorder stays down until the user turns it back on.
            if (AppPreferences(context).getRecordingState() == RecordingState.STOPPED) {
                AppLogger.i(TAG, "Recording is STOPPED by the user: not starting the keep-alive")
                stop(context)
                return true
            }
            // Runs in every mode (P1a): in Shizuku mode it is the process anchor + status notice (and the app-call
            // detector when those are on), never our ADB daemon — see [KeepAlivePolicy].
            logDecisionIfChanged(context)
            val outcome = ForegroundStartGuard.attempt {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, DaemonKeepAliveService::class.java).apply {
                        if (retryAttempt > 0) putExtra(EXTRA_RETRY_ATTEMPT, retryAttempt)
                    },
                )
            }
            if (outcome is ForegroundStartGuard.Outcome.Refused) {
                ForegroundStartGuard.keepAliveStartRefused = true
                AppLogger.w(TAG, "Failed to start keep-alive service: ${outcome.cause.message}")
                UsbDebuggingEvents.record(context, ForegroundStartGuard.describe("keep-alive start", outcome.cause, batteryExempt = false))
                return false
            }
            return true
        }

        /** Stop the keep-alive anchor (e.g. if the user disables persistence). */
        fun stop(context: Context) {
            // Clear the relaunch hook FIRST, and synchronously.
            //
            // `stopService` only *asks*; the service keeps running until the framework delivers
            // onDestroy, and that is where the hook used to be cleared. In the window between the two, a
            // binder death still triggers an immediate relaunch — and a mode switch produces exactly
            // that death, on purpose, one line later.
            //
            // Measured on the OP9 switching into Shizuku mode: the death signal fired at 11:46:49.893,
            // 46 ms BEFORE "Privileged mode is now SHIZUKU", the keep-alive relaunched our ADB daemon,
            // and that daemon's killStaleRecorders then killed the Shizuku service that had just
            // started. The phone settled in Shizuku mode being served by our own ADB daemon — a wrong
            // host, silently, with every layer reporting success.
            RecorderConnection.onDeath = null
            // A retry queued earlier must not bring back a keep-alive that was stopped on purpose (review R3).
            runCatching { KeepAliveRetry.cancel(context) }
            runCatching { context.stopService(Intent(context, DaemonKeepAliveService::class.java)) }
        }
    }
}
