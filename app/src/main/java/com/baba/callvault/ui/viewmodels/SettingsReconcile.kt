/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.viewmodels

import android.content.Context
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.services.recording.VoipToggleEffects
import com.baba.callvault.system.storage.RetentionScheduler
import com.baba.callvault.system.storage.SyncScheduler
import com.baba.callvault.system.updates.UpdateScheduler
import com.baba.callvault.transcription.TranscriptionScheduler
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * What an imported settings file has to do after the preferences are written (review C96).
 *
 * Importing only wrote preferences, so a file that turned "Record app calls" on left detection unregistered and the
 * policy unarmed until the next process start (app calls lost while Settings showed the switch on), and a changed
 * transcription, sync, retention or update schedule was not applied. This replays what the individual toggles do:
 * the same effect functions, nothing new.
 *
 * Order: the mode check first (a switch this phone or mode cannot honour is turned off, as at app start), then the
 * app-call effect on the caller's thread (the toggle's own effects start the foreground service, which wants the
 * foreground tap), then the four schedulers off the main thread in a process-wide scope, so closing Settings cannot
 * cancel half of them. Each step logs its failure and never stops the next.
 */
object SettingsReconcile {

    private const val TAG = "CV:SettingsReconcile"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    class Effects(
        val reconcileModes: () -> Unit,
        val isVoipOn: () -> Boolean,
        val applyVoip: (Boolean) -> Unit,
        val applyTranscription: () -> Unit,
        val applySync: () -> Unit,
        val applyRetention: () -> Unit,
        val applyUpdates: () -> Unit,
    )

    /** The real effects, the very ones the Settings toggles run. */
    fun effectsFor(context: Context): Effects {
        val app = context.applicationContext
        val prefs = AppPreferences(app)
        return Effects(
            reconcileModes = { prefs.disableWhatModeCannotDo(prefs.getPrivilegedMode()) },
            isVoipOn = { prefs.isVoipRecordingEnabled() },
            applyVoip = { on -> VoipToggleEffects.apply(app, on) },
            applyTranscription = { TranscriptionScheduler.apply(app) },
            applySync = { SyncScheduler.apply(app) },
            applyRetention = { RetentionScheduler.apply(app) },
            applyUpdates = { UpdateScheduler.apply(app) },
        )
    }

    /**
     * @param voipBefore whether app calls were on before the import.
     * @return the job of the background part, for tests to wait on.
     */
    fun afterImport(
        voipBefore: Boolean,
        effects: Effects,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): Job {
        step("mode check", effects.reconcileModes)
        val voipNow = runCatching(effects.isVoipOn).getOrDefault(voipBefore)
        if (voipNow != voipBefore) {
            AppLogger.i(TAG, "Import changed app calls to ${if (voipNow) "on" else "off"}; applying it now")
            step("app calls", { effects.applyVoip(voipNow) })
        }
        return scope.launch(dispatcher) {
            step("transcription schedule", effects.applyTranscription)
            step("sync schedule", effects.applySync)
            step("retention schedule", effects.applyRetention)
            step("update check", effects.applyUpdates)
        }
    }

    private fun step(what: String, block: () -> Unit) {
        runCatching(block).onFailure { AppLogger.e(TAG, "Applying the import's $what failed: ${it.message}", it) }
    }
}
