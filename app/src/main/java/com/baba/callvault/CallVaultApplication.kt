/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault

import android.app.Application
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.server.RecorderBackend
import com.baba.callvault.server.ShizukuBackend
import com.baba.callvault.services.recording.DaemonKeepAliveService
import com.baba.callvault.services.debug.DebugNotificationHelper
import com.baba.callvault.system.storage.RetentionScheduler
import com.baba.callvault.system.storage.SyncScheduler
import com.baba.callvault.summary.SummaryModel
import com.baba.callvault.transcription.TranscriptionQueue
import com.baba.callvault.transcription.model.ModelRepository
import com.baba.callvault.transcription.model.TranscriptionModel
import com.baba.callvault.system.updates.UpdateScheduler
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.services.recording.VoipCaptureController
import com.baba.callvault.utils.AppLogger
import com.baba.callvault.system.health.SilentFailureNotifier
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

/**
 * CallVaultApplication is run when the app process is created. Can be seen as the very first entry point of the app.
 */
class CallVaultApplication : Application() {
    private companion object {
        const val TAG = "CV:CallVaultApplication"

        /** Old CallMonitorService notification id (pre-consolidation) — now shares 4720; cancel the stale one. */
        const val LEGACY_READINESS_NOTIF_ID = 4714

        /** Old RecorderReadinessNotifier notification id (pre-consolidation, transient launch notice). */
        const val LEGACY_LAUNCH_NOTIF_ID = 4715
    }

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(applicationContext)

        // Shizuku mode has no keep-alive, so nothing else would notice Shizuku dying while the app runs.
        com.baba.callvault.server.ShizukuLifecycleWatcher.install(applicationContext)

        // Reclaim model files no version of the app will ever use again.
        //
        // Model entries name the exact file they fetch, and `ModelRepository.delete` only removes a
        // file some *current* entry claims. So changing a model's filename — a better quantisation, a
        // newer build — leaves the old one behind, invisible to the app and impossible to remove from
        // inside it. Swapping the summariser on 2026-08-27 stranded 3.46 GB on a real phone this way.
        //
        // The whitelist is built here rather than inside the repository because this is the only place
        // that can see BOTH model families; the two share a directory, and a sweep that knew about only
        // one would delete the other. Off the main thread: this touches the filesystem.
        Thread {
            runCatching {
                val keep = TranscriptionModel.entries.map { it.fileName }.toSet() +
                    SummaryModel.entries.map { it.fileName }.toSet()
                val freed = ModelRepository.pruneOrphans(ModelRepository.modelsDir(applicationContext), keep)
                if (freed > 0L) AppLogger.i(TAG, "Reclaimed $freed bytes of superseded model files")
            }.onFailure { AppLogger.w(TAG, "Could not prune orphaned model files: ${it.message}") }
        }.apply { isDaemon = true; start() }

        // Migration: pre-consolidation builds showed readiness from THREE sources (the transient launch
        // notifier id 4715 + the post-boot CallMonitorService id 4714), duplicating the single permanent
        // keep-alive notification (id 4720). Cancel those stale ids once on startup so an updated install
        // immediately drops to ONE readiness notification instead of waiting for a reboot to clear them.
        runCatching {
            getSystemService(android.app.NotificationManager::class.java)?.apply {
                cancel(LEGACY_READINESS_NOTIF_ID); cancel(LEGACY_LAUNCH_NOTIF_ID)
            }
        }

        // The VoIP capture policy lives in the daemon and dies with it, and it must be armed BEFORE a
        // call starts — there is no arming it once a call is under way. Re-arm on every fresh daemon
        // binder. Off the binder thread: arming is a blocking IPC.
        RecorderConnection.onDaemonReady = {
            // A fresh host collects nothing until it is told to. Do this first and cheaply, so a daemon
            // that relaunched mid-session is logging again before anything else happens to it.
            RecorderBackend.syncDiagnostics(applicationContext)
            if (AppPreferences(applicationContext).isVoipRecordingEnabled()) {
                Thread {
                    runCatching { VoipCaptureController.sync(applicationContext) }
                        .onFailure { AppLogger.w(TAG, "VoIP re-arm failed: ${it.message}") }
                }.apply { isDaemon = true; name = "cv-voip-rearm" }.start()
            }
        }

        // Push the diagnostics switch once at start as well as on every fresh binder.
        //
        // onDaemonReady only fires when a binder ARRIVES. A daemon that was already alive when the app
        // started never fires it, so it collected nothing — measured on the OP9, where a report was
        // exported at 15:00:00 and the host was not told to collect until 15:00:09, when the user
        // happened to toggle logging. The report said "Recorder host lines: none" for a daemon that had
        // been running throughout.
        Thread {
            runCatching { RecorderBackend.syncDiagnostics(applicationContext) }
                .onFailure { AppLogger.d(TAG, "Diagnostics sync at start failed: ${it.message}") }
        }.apply { isDaemon = true; name = "cv-diag-sync" }.start()

        // Re-assert the "debug logging is on" reminder if the user left logging enabled across an
        // app restart, so the nudge to turn it back off survives process death.
        runCatching { DebugNotificationHelper.sync(applicationContext) }
            .onFailure { AppLogger.w(TAG, "Debug notification sync failed: ${it.message}") }

        // Reconcile the daily retention sweep with the saved prefs (schedules it when retention is on,
        // cancels it when off). Idempotent; ensures the sweep persists across reinstalls/reboots.
        runCatching { RetentionScheduler.apply(applicationContext) }
            .onFailure { AppLogger.w(TAG, "Retention scheduler apply failed: ${it.message}") }

        // Reconcile the daily update check with the saved prefs (same idempotent pattern), then run
        // an immediate throttled check so a new release surfaces on app open, not just once a day.
        runCatching {
            UpdateScheduler.apply(applicationContext)
            UpdateScheduler.checkNowIfDue(applicationContext)
        }.onFailure { AppLogger.w(TAG, "Update scheduler apply failed: ${it.message}") }

        // Reconcile the periodic cloud sweep too. It was only ever applied when the wizard was
        // *finished*, while the cadence pref is written the moment it is tapped — so backing out of the
        // wizard (or being killed in it) could leave a scheduled sweep behind for a cadence the app no
        // longer uses, batch-uploading alongside the per-recording copy.
        runCatching { SyncScheduler.apply(applicationContext) }
            .onFailure { AppLogger.w(TAG, "Sync scheduler apply failed: ${it.message}") }

        // Release any transcript row left RUNNING by a run that no longer exists. A cancelled or
        // killed worker cannot tidy up after itself — whisper sits inside a blocking native call, so
        // the interruption may never reach the Kotlin that would reset the row. Left alone the row
        // shows an untappable spinner for ever, and the queue skips RUNNING so no automatic sweep
        // would rescue it either. A fresh process means nothing from before is still transcribing.
        Thread {
            runCatching { runBlocking { TranscriptionQueue.releaseStaleWork(applicationContext) } }
                .onSuccess { if (it > 0) AppLogger.i(TAG, "Released $it stale transcription row(s)") }
                .onFailure { AppLogger.w(TAG, "Releasing stale transcription rows failed: ${it.message}") }
        }.start()

        // If ADB was already paired, proactively bring up the persistent recorder daemon in the
        // background: this (transiently) enables Wireless debugging if needed, launches the daemon,
        // waits for its binder, then turns WD back OFF — so the app is recording-ready over binder and
        // WD is off when idle, with no user action. Best-effort; the call path also ensures it on demand.
        // Reconcile the opt-ins with what the CURRENT mode can do, not only when the mode changes.
        // Someone already in Shizuku mode when this shipped never goes through a switch, so their
        // VoIP and resilient-recording switches would stay on while being unable to work — measured
        // on the OP9, where handoff was still enabled after a mode round trip. Only ever turns things
        // off, and only what the mode cannot do, so it is safe to run on every start.
        runCatching {
            val prefs = AppPreferences(applicationContext)
            val mode = prefs.getPrivilegedMode()
            // Both directions, for the same reason the disable runs here: a mode change that was
            // interrupted — or that happened before this app version — never completed its reconcile.
            // Restoring is a no-op in a mode that still cannot do the thing, and keeps the record.
            val restored = prefs.restoreWhatModeCanDoAgain(mode)
            if (restored.isNotEmpty()) {
                AppLogger.i(TAG, "Turned back on at start (supported in $mode): ${restored.joinToString()}")
            }
            val turnedOff = prefs.disableWhatModeCannotDo(mode)
            if (turnedOff.isNotEmpty()) {
                AppLogger.i(TAG, "Turned off on start (unsupported in this mode): ${turnedOff.joinToString()}")
            }
        }.onFailure { AppLogger.w(TAG, "Capability reconcile failed: ${it.message}") }

        // In standalone mode, make sure no Shizuku user service is left running. One bound with
        // daemon(true) outlives the app and even survives an app update, so a phone that used Shizuku
        // once can otherwise carry a second shell-uid recorder for ever — and either of them may hold
        // the binder the app talks to. Observed on the OP9: two com.baba.callvault:recorder processes
        // still alive while the app was recording through its own ADB daemon.
        //
        // Safe to run unconditionally: stop() is a no-op when there is nothing to remove.
        runCatching {
            if (AppPreferences(applicationContext).getPrivilegedMode().needsShizuku) {
                // Also on every start, not only on a switch: a keep-alive can outlive the process that
                // started it, and one left running in Shizuku mode is a foreground service burning
                // battery to watch for a daemon that must not be there.
                DaemonKeepAliveService.stop(applicationContext)
            } else {
                ShizukuBackend.stop(remove = true)
            }
        }.onFailure { AppLogger.d(TAG, "Nothing left over to clear: ${it.message}") }

        // isPrivilegedTransportSetUp, not isAdbPaired: a Shizuku user never pairs, and gating on
        // pairing meant the app never bound Shizuku at startup — so the first call after a cold
        // start raced an unbound recorder.
        if (AppPreferences(applicationContext).isPrivilegedTransportSetUp()) {
            Thread {
                // Warm the persistent daemon in the background so the app is recording-ready over binder.
                // Readiness ("starting up… → ready to record") is surfaced by the SINGLE persistent
                // DaemonKeepAliveService notification — no separate notifier here, which previously
                // produced a DUPLICATE readiness notification alongside the keep-alive one.
                val started = runCatching { RecorderBackend.ensureRunning(applicationContext) }
                    .onFailure { AppLogger.w(TAG, "Startup recorder-daemon warmup failed: ${it.message}") }
                    .getOrDefault(false)
                // Clear only; never warn from here. The boot path raises the warning because nobody
                // is looking then — but the app being open IS someone looking, and the Home status
                // card already explains it in place. What must not happen is a warning from a
                // previous boot outliving the problem and teaching the user to ignore the next one.
                if (started) SilentFailureNotifier.clearRecorderUnavailable(applicationContext)
                CoroutineScope(Dispatchers.IO).launch {
                    SilentFailureNotifier.checkSyncHealth(applicationContext)
                }
            }.apply { isDaemon = true }.start()
        }
    }
}