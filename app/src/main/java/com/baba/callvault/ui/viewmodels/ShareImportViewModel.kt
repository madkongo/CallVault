/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.viewmodels

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.recordings.AudioImport
import com.baba.callvault.data.recordings.SharedAudio
import com.baba.callvault.onboarding.OnboardingStatus
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs one shared file into the library, and holds what to say about it.
 *
 * **Why a ViewModel and not the Activity.** A copy takes as long as the file is big, and the decode
 * probe opens a codec — so rotating the phone half way through a share would, in an Activity-scoped
 * job, cancel the work between the copy and the catalogue and leave a file sitting in the user's
 * recordings folder that the app has no record of. That is the same reason
 * [HomeViewModel.importAudio] runs where it does, and it is worth the second copy of the reasoning:
 * the two paths must not drift.
 *
 * The state is a `StateFlow` rather than a callback for the same reason. The Activity is rebuilt on
 * a rotation and re-reads whatever the last state was; a callback would have been delivered to an
 * Activity that no longer exists.
 */
class ShareImportViewModel(application: Application) : AndroidViewModel(application) {

    /** What the share screen is showing. */
    sealed interface State {

        /** Checking what arrived and, if it is good, copying it. One spinner covers both. */
        data object Working : State

        /** The file is in the library under [displayName]. */
        data class Imported(val displayName: String) : State

        /** The share itself was no good — see [SharedAudio.Reason]. */
        data class ShareRefused(val reason: SharedAudio.Reason) : State

        /** The file was no good — see [AudioImport.Reason]. Nothing was kept. */
        data class ImportRefused(val reason: AudioImport.Reason) : State
    }

    private val _state = MutableStateFlow<State>(State.Working)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Guards against a second run of the same share.
     *
     * The Activity starts this from a composition effect, and a composition can be re-entered — by a
     * rotation, by the app lock resolving, by a theme change. A second run would copy the same file
     * in again under a second name, leaving the user two rows and, later, two transcripts.
     */
    private var hasBegun = false

    /**
     * Whether a copy is actually in flight, as opposed to not started yet.
     *
     * [State.Working] alone cannot answer this: it is also the state of a share sitting behind the
     * app lock, which has read nothing and copied nothing. The Activity asks before it decides
     * whether it is safe to finish itself, and the difference is a file half-imported.
     */
    val isBusy: Boolean get() = hasBegun && _state.value is State.Working

    /**
     * Takes the share apart, decides what it is, and imports it if it is anything.
     *
     * @param action the incoming Intent's action, passed in rather than read here so the decision
     *   ([SharedAudio.verdictFor]) stays a pure function of what arrived.
     * @param source the `EXTRA_STREAM` URI, or null when the share carried none.
     */
    fun begin(action: String?, source: Uri?) {
        if (hasBegun) return
        hasBegun = true
        viewModelScope.launch {
            // Off the main thread deliberately. Reading whether setup has finished touches
            // SharedPreferences, the package manager and — on a phone that has NOT finished — the
            // ADB connection manager, whose lock has been seen held by a thread parked inside
            // libadb. None of that belongs on the thread drawing the spinner.
            val isAppSetUp = withContext(Dispatchers.IO) { isAppSetUp(getApplication()) }
            when (val verdict = SharedAudio.verdictFor(action, source != null, isAppSetUp)) {
                is SharedAudio.Verdict.Refuse -> {
                    AppLogger.w(TAG, "Share refused (${verdict.reason}): action=$action, stream=${source != null}")
                    _state.value = State.ShareRefused(verdict.reason)
                }
                SharedAudio.Verdict.Import -> {
                    // Not null: the verdict is Import only when a stream was present.
                    val outcome = AudioImport.import(getApplication(), requireNotNull(source))
                    _state.value = when (outcome) {
                        is AudioImport.Outcome.Imported -> State.Imported(outcome.displayName)
                        is AudioImport.Outcome.Refused -> State.ImportRefused(outcome.reason)
                    }
                }
            }
        }
    }

    /**
     * Whether the app has finished its one-time setup.
     *
     * The same two gates the router climbs — every prerequisite granted, then the wizard finished —
     * asked of the same [OnboardingStatus], so the share target and `MainActivity` cannot disagree
     * about whether this app is set up. If they could, a share would be accepted here and then land
     * the user in the wizard when they tapped Open.
     */
    private fun isAppSetUp(context: Context): Boolean {
        val status = OnboardingStatus.getStatus(context, AppPreferences(context))
        return status.isComplete() && status.wizardCompleted
    }

    private companion object {
        const val TAG = "CV:ShareImport"
    }
}
