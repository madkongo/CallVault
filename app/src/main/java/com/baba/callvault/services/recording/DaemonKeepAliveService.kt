/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.health.SetupHealthStore
import com.baba.callvault.integrations.adb.AdbShell
import com.baba.callvault.integrations.adb.DeveloperOptions
import com.baba.callvault.integrations.adb.UsbDefaultConfig
import com.baba.callvault.integrations.adb.UsbNotice
import com.baba.callvault.integrations.adb.WirelessDebuggingPolicy
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.server.RecorderBackend
import com.baba.callvault.server.RecorderServerLauncher
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
 */
class DaemonKeepAliveService : Service() {

    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val voipDetector by lazy {
        VoipCallDetector(applicationContext).apply {
            // Reuse the permanent keep-alive notification to show that a VoIP call is being recorded,
            // rather than adding a second one. Without this the user has no sign it is working.
            onRecordingStateChanged = { updateNotification(RecorderConnection.isConnected) }
        }
    }
    private var lastReady: Boolean? = null

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
            val alive = isDaemonAlive()
            if (alive != (lastReady == true)) {
                lastReady = alive
                updateNotification(alive)
                AppLogger.d(TAG, "keep-alive: daemon alive=$alive")
                // The daemon has just come up, so the shell is free and nothing is racing us — the calm
                // moment to find out the USB mode if we never managed to. The recording path's own
                // attempt gets one un-retried shot while a call is starting, and on the device in issue
                // #22 that shot always failed, leaving the advice permanently unsayable.
                if (alive) readUsbDefaultIfStillUnknown()
            } else if (!alive) {
                // Down and still down: what the notice should say can change without the daemon
                // changing — the failure streak trips, Wi-Fi drops or returns — so re-read it each tick
                // rather than leave "starting up" on screen for a recovery that cannot happen (#39).
                updateNotification(false)
            }
            if (alive) {
                downStreak = 0
            } else {
                downStreak++
                // Debounce: act only after DOWN_STREAK_THRESHOLD consecutive down reads, so a transient
                // binder blip doesn't trigger a relaunch (which would kill+respawn a daemon that was fine).
                if (downStreak >= DOWN_STREAK_THRESHOLD) maybeRewarm()
            }
            watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
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
            val usbOn = AdbShell.isUsbDebuggingEnabled(applicationContext)
            val wdOn = AdbShell.isWirelessDebuggingEnabled(applicationContext)
            // The notice names these switches, so it has to follow them immediately.
            updateNotification(isDaemonAlive())

            // Both directions matter, and both are immediate. Waiting for the next daemon launch to
            // re-evaluate means the user flips a switch and nothing visibly happens, which reads as
            // broken even when it eventually corrects itself.
            val reason = when {
                // USB debugging just went off. Stock Android stops adbd on that change whatever Wireless
                // debugging reads (init.usb.configfs.rc: sys.usb.config=none → stop adbd), and the daemon
                // dies with it. This is a user-caused unrecordable window opening RIGHT NOW, so the
                // observation window restarts immediately.
                !usbOn -> {
                    restartObservationWindow("USB debugging switched off")
                    "USB debugging switched off — adbd stops with it; restarting it once the USB change settles"
                }
                // USB debugging now holds adbd up, so Wireless debugging is no longer needed. Dropping
                // it here is safe: with USB debugging enabled, toggling Wireless debugging does not
                // restart adbd (measured — its pid is unchanged), so the daemon is never at risk.
                wdOn -> "USB debugging switched on — Wireless debugging is no longer needed"
                else -> return
            }

            AppLogger.i(TAG, reason)
            Thread {
                runCatching {
                    if (!usbOn) {
                        // Not at once. Writing Wireless debugging on within milliseconds of the USB change
                        // loses a race measured on the emulator: init starts a fresh adbd, then the
                        // still-running USB transition stops it, leaving Wireless debugging reading on with
                        // nothing listening — #39's state exactly.
                        Thread.sleep(USB_CHANGE_SETTLE_MS)
                        AdbShell.reviveAdbdIfStopped(applicationContext, "USB debugging switched off")
                    }
                    // Re-runs the transport policy: with the daemon already connected this is just the
                    // decision, no relaunch — and it is what switches Wireless debugging off.
                    RecorderBackend.ensureRunning(applicationContext)
                }.onFailure { AppLogger.w(TAG, "Could not apply the transport change: ${it.message}") }
            }.apply { isDaemon = true; name = "cv-transport-change" }.start()
        }
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
            val on = AdbShell.isWirelessDebuggingEnabled(applicationContext)
            updateNotification(isDaemonAlive())
            if (AdbShell.didWeJustSetWirelessDebugging(enabled = on)) return
            AppPreferences(applicationContext).setWirelessDebuggingEnabledByUs(false)
            AppLogger.i(TAG, "Wireless debugging switched ${if (on) "on" else "off"} by hand; it is the user\'s to manage")
        }
    }

    /**
     * Watches the Developer options master toggle for the same reason as [usbDebuggingObserver]: with
     * it off, Wireless debugging cannot function and the daemon cannot run — a user-caused unrecordable
     * window that must be recorded the instant it opens, not discovered the next time the user happens
     * to open CallVault.
     */
    private val developerOptionsObserver = object : ContentObserver(watchdogHandler) {
        override fun onChange(selfChange: Boolean) {
            if (!DeveloperOptions.isExplicitlyDisabled(applicationContext)) return
            restartObservationWindow("Developer options switched off")
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_readiness_channel), NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )

        runCatching {
            contentResolver.registerContentObserver(
                Settings.Global.getUriFor("adb_enabled"), false, usbDebuggingObserver,
            )
            contentResolver.registerContentObserver(
                Settings.Global.getUriFor("adb_wifi_enabled"), false, wirelessDebuggingObserver,
            )
            contentResolver.registerContentObserver(
                Settings.Global.getUriFor("development_settings_enabled"), false, developerOptionsObserver,
            )
        }.onFailure { AppLogger.w(TAG, "Could not watch the debugging switches: ${it.message}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Stand down if the mode changed underneath us. start() guards the entry, but this service
        // outlives a mode switch, and a keep-alive left over from standalone goes on relaunching the ADB
        // daemon — which is how an ADB daemon turned up in Shizuku mode after a switch, on the OP9.
        if (standDownIfShizuku()) return START_NOT_STICKY

        val startedForeground = runCatching {
            // While a call is recorded the shared notification shows the recording, with its controls;
            // starting here must not swap that for "Ready". See [SharedStatusNotice].
            startForeground(
                NOTIF_ID,
                SharedStatusNotice.contentForKeepAlive(buildNotification(RecorderConnection.isConnected)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        }.onFailure { AppLogger.e(TAG, "keep-alive startForeground failed", it) }.isSuccess
        if (!startedForeground) {
            stopSelf()
            return START_NOT_STICKY
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
            voipDetector.startNow()
        }
        if (intent?.action == ACTION_CARRIER_CALL_STARTED) {
            voipDetector.abortForCarrierCall()
        }

        // Recover the INSTANT the daemon dies (binder linkToDeath) — don't wait for the next poll.
        // On a real incoming call this is what races (and hopefully beats) the call after a long idle.
        RecorderConnection.onDeath = { onDaemonDiedImmediate() }
        // A recorded call has let go of the shared notification. Android does not cancel an id this service
        // still holds, so the recording's content would stay up after the call unless "Ready" goes back.
        SharedStatusNotice.onReleased = { watchdogHandler.post { updateNotification(isDaemonAlive()) } }
        // VoIP detection lives here rather than in its own component: this service is already a
        // permanent foreground presence, so watching for VoIP calls costs no extra process and no
        // second notification, and VoIP gets exactly the same lifetime as carrier recording.
        voipDetector.sync()

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
            AppLogger.i(TAG, "keep-alive: binder-death signal — relaunching immediately")
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
        val offline = runCatching { AppPreferences(this).isOfflineRecordingEnabled() }.getOrDefault(false)
        if (!offline && !isWifiConnected()) return // the WD relaunch path needs Wi-Fi; loopback doesn't
        // Checked AFTER the Wi-Fi guard so a relaunch we never attempt does not consume the throttle.
        if (!rewarmGate.tryEnter(SystemClock.elapsedRealtime(), force)) return
        Thread {
            AppLogger.i(TAG, "keep-alive: daemon down — relaunching (force=$force offline=$offline)")
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
            runCatching { AdbShell.reviveAdbdIfStopped(applicationContext, "keep-alive relaunch") }
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
                    runCatching { AdbShell.enableWirelessDebugging(applicationContext) }
                        .onFailure { AppLogger.w(TAG, "keep-alive: could not re-enable Wireless debugging: ${it.message}") }
                }

                RecoveryStep.REBUILD_CONNECTION -> {
                    // The endpoint looks fine and attempts still time out, so stop trusting it: drop the
                    // connection outright, and make sure Wireless debugging is on so there is a second
                    // endpoint to reach even if the loopback one is the wedged half. Manually enabling
                    // Wireless debugging is exactly what un-wedged the 2026-08-18 device.
                    AppLogger.w(TAG, "keep-alive: relaunch keeps timing out — rebuilding the ADB connection")
                    runCatching { AdbShell.dropConnection(applicationContext) }
                        .onFailure { AppLogger.w(TAG, "keep-alive: could not drop the ADB connection: ${it.message}") }
                    runCatching { AdbShell.enableWirelessDebugging(applicationContext) }
                        .onFailure { AppLogger.w(TAG, "keep-alive: could not re-enable Wireless debugging: ${it.message}") }
                }

                RecoveryStep.CONNECT -> Unit
            }
            val ok = try {
                launchDaemonBounded()
            } finally {
                // ALWAYS release, even if the bounded launch threw. The gate expiring is the safety net;
                // this is the normal path, and leaving it to the net would cost a whole stuck window.
                rewarmGate.leave()
            }
            // Feed the outcome back so a run of failures escalates instead of repeating unchanged.
            if (ok) recoveryPolicy.onAttemptSucceeded() else recoveryPolicy.onAttemptFailed()
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
     * On timeout the worker is abandoned (it is a daemon thread) and the ADB connection is dropped, which
     * unblocks its read so it can die and release the lock. Without that drop the abandoned thread keeps
     * `heavyOperationLock` and every later attempt piles up behind it — bounded, but never succeeding.
     */
    private fun launchDaemonBounded(): Boolean {
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
                    "dropping the ADB connection so it can unwind and free the lock",
            )
            AdbShell.dropConnection(applicationContext)
            return false
        }
        return ok.get()
    }

    private fun isWifiConnected(): Boolean = runCatching {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }.getOrDefault(false)

    /** Tells the user CallVault undid their Wireless-debugging change, and why. Dismissible, silent. */

    private fun updateNotification(ready: Boolean) {
        // A recorded call owns the notification — posting now would replace Pause, Mark and Stop, the only
        // controls during a call, with "Ready to record calls". The release puts this back afterwards.
        if (SharedStatusNotice.isHeldByRecording) return
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(ready))
        }
    }

    private fun buildNotification(ready: Boolean): Notification {
        val voipRecording = runCatching { voipDetector.isRecording }.getOrDefault(false)
        val notice = ReadinessNoticeText.current(this, ready)
        val baseText = when {
            voipRecording -> getString(R.string.notif_voip_recording_text)
            else -> getString(ReadinessNoticeText.text(notice))
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(
                when {
                    voipRecording -> getString(R.string.notif_voip_recording_title)
                    else -> getString(ReadinessNoticeText.title(notice))
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
        val usbNotice = UsbDefaultConfig.noticeFor(UsbDefaultConfig.cached(this), ready)
        if (usbNotice != UsbNotice.NONE) {
            val warning = getString(
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
        if (ready && WirelessDebuggingPolicy.mustKeepWirelessDebugging(AdbShell.wirelessDebuggingPlan(this))) {
            val notice = getString(R.string.notif_wd_required)
            builder.setContentText(notice)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$baseText\n$notice"))
        }
        return builder.build()
    }

    override fun onDestroy() {
        runCatching { contentResolver.unregisterContentObserver(usbDebuggingObserver) }
        runCatching { contentResolver.unregisterContentObserver(wirelessDebuggingObserver) }
        runCatching { contentResolver.unregisterContentObserver(developerOptionsObserver) }
        runCatching { voipDetector.stop() }
        watchdogHandler.removeCallbacks(watchdog)
        RecorderConnection.onDeath = null
        SharedStatusNotice.onReleased = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** True when this service has no business running, having stopped itself. */
    private fun standDownIfShizuku(): Boolean {
        if (!AppPreferences(applicationContext).getPrivilegedMode().needsShizuku) return false
        AppLogger.i(TAG, "Shizuku mode: the keep-alive has nothing to keep alive; stopping")
        stopSelf()
        return true
    }

    companion object {
        private const val TAG = "CV:DaemonKeepAlive"
        private const val CHANNEL_ID = "recorder_keepalive"

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

        /** Low-importance channel for one-off explanations, kept apart from the permanent status note. */
        private const val NOTIF_ID = SharedStatusNotice.ID

        /** How often the watchdog checks the daemon is alive. Cheap (a binder ping). */
        private const val WATCHDOG_INTERVAL_MS = 60_000L

        /**
         * How long to let a USB debugging change finish before touching Wireless debugging. The race it
         * avoids was 24 ms wide on the emulator; this is generous on purpose, since a slow phone costs only
         * seconds of an already-unrecordable window.
         */
        private const val USB_CHANGE_SETTLE_MS = 3_000L

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

        /** Start (or no-op if already running) the persistent keep-alive anchor. Safe to call repeatedly. */
        fun start(context: Context) {
            // Nothing to keep alive in Shizuku mode, and worse than nothing: this service relaunches
            // OUR daemon and turns Wireless debugging on to do it — on a phone whose owner chose
            // Shizuku precisely so none of that would happen. Shizuku restarts its own user service
            // (bound with daemon(true)), so the job this exists for is already someone else's.
            if (AppPreferences(context).getPrivilegedMode().needsShizuku) {
                AppLogger.i(TAG, "Shizuku mode: not starting the keep-alive; Shizuku owns the recorder")
                stop(context)
                return
            }
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, DaemonKeepAliveService::class.java))
            }.onFailure { AppLogger.w(TAG, "Failed to start keep-alive service: ${it.message}") }
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
            runCatching { context.stopService(Intent(context, DaemonKeepAliveService::class.java)) }
        }
    }
}
