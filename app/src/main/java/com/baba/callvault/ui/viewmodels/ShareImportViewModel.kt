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
import com.baba.callvault.data.recordings.ImportFollowUp
import com.baba.callvault.data.recordings.ImportedRecording
import com.baba.callvault.data.recordings.SharedAudio
import com.baba.callvault.data.transcripts.TranscriptRepository
import com.baba.callvault.onboarding.OnboardingStatus
import com.baba.callvault.transcription.TranscriptionEstimate
import com.baba.callvault.transcription.model.ModelRepository
import com.baba.callvault.transcription.model.TranscriptionModel
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
 *
 * ## Why the card asks before it copies
 *
 * It used to import immediately and offer to open the result. The maintainer's objection is the
 * right one: someone sharing a voice note often wants the *words*, and wanting the words does not
 * mean wanting a stranger's audio filed among their own calls for ever. So the first thing the card
 * does is ask, and the answer — [ImportedRecording.Kind] — decides the name the file is stored
 * under and therefore everything that follows from it.
 *
 * ## Why the whole transcription flow is here
 *
 * Both answers end in a transcription, and an import always asks the language and always shows the
 * estimate whatever the two "don't ask" settings say: the settings were turned off by someone who
 * had seen the numbers for their own calls, which are minutes long and in the language they speak.
 * A shared file is neither, and may be an hour of a lecture. Those two asks therefore have to happen
 * *here*, on the card, because there is no other screen in this flow — sending the user into the app
 * to answer them would be the detour the card exists to avoid.
 */
class ShareImportViewModel(application: Application) : AndroidViewModel(application) {

    /** What the share screen is showing. */
    sealed interface State {

        /** Working out what arrived. Nothing has been read and nothing copied. */
        data object Reading : State

        /**
         * Asking what the file is for. Nothing has been copied yet.
         *
         * @param displayName the source's own name, or null when the provider would not say.
         * @param sizeBytes the source's length in bytes, or a negative value when unknown. The
         *   *duration* is not offered: reading it needs the audio, and the audio needs the copy this
         *   card has deliberately not made yet — a share URI is frequently a non-seekable pipe, so
         *   probing the source is exactly what does not work for the files this feature exists for.
         */
        data class Deciding(val displayName: String?, val sizeBytes: Long) : State

        /** Copying the file in and checking this phone can read it. */
        data object Importing : State

        /** Which language to transcribe [label] in. */
        data class AskLanguage(val displayName: String, val label: String) : State

        /** How long it will take, before committing the phone to it. */
        data class Confirm(
            val displayName: String,
            val label: String,
            val estimateMs: Long?,
            val isFirstRun: Boolean,
            val language: String?,
        ) : State

        /** Imported and queued. [kind] decides what the card promises will happen to the audio. */
        data class Queued(val displayName: String, val kind: ImportedRecording.Kind) : State

        /**
         * Imported, but the user backed out of the language or estimate question.
         *
         * A distinct state rather than a silent close, because what happened is not nothing: the
         * file is on the phone. Where it is depends on [kind], and the card says so.
         */
        data class NotTranscribed(val displayName: String, val kind: ImportedRecording.Kind) : State

        /**
         * Imported, but longer than transcription can decode. The audio is kept either way — see the
         * note in [afterImport] for where a transcribe-only file then shows up.
         */
        data class TooLong(val minutes: Int) : State

        /** Imported, but there is no model to read it with. The audio is kept. */
        data object NoModel : State

        /** The share itself was no good — see [SharedAudio.Reason]. */
        data class ShareRefused(val reason: SharedAudio.Reason) : State

        /** The file was no good — see [AudioImport.Reason]. Nothing was kept. */
        data class ImportRefused(val reason: AudioImport.Reason) : State
    }

    private val _state = MutableStateFlow<State>(State.Reading)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Guards against a second run of the same share.
     *
     * The Activity starts this from a composition effect, and a composition can be re-entered — by a
     * rotation, by the app lock resolving, by a theme change. A second run would offer the card
     * twice and, once answered, copy the same file in again under a second name.
     */
    private var hasBegun = false

    /** Guards against a double tap on the two choice buttons starting two copies of one file. */
    private var hasChosen = false

    /**
     * Whether a copy is actually in flight, as opposed to waiting on the user.
     *
     * The Activity asks before it decides whether it is safe to finish itself, and the difference is
     * a file half-imported. Only the copy counts: a card sitting on a question — behind the app lock,
     * or on the two buttons, or on the language dialog — has copied nothing, and finishing it is how
     * the NEXT share gets through (see the note in `ShareImportActivity.onStop`).
     */
    val isBusy: Boolean get() = _state.value is State.Importing

    /**
     * Takes the share apart, decides what it is, and offers the two answers if it is anything.
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
                    this@ShareImportViewModel.source = requireNotNull(source)
                    // One provider query, no bytes. Enough to let the user recognise the file they
                    // just shared, which is the whole job of the question card.
                    val described = withContext(Dispatchers.IO) {
                        AudioImport.describe(getApplication(), requireNotNull(source))
                    }
                    _state.value = State.Deciding(described.displayName, described.sizeBytes)
                }
            }
        }
    }

    /** The URI to import, held from [begin] to [choose]. Never read before the verdict allows it. */
    private var source: Uri? = null

    /**
     * The user has said what the file is for. Copies it in under the matching name, then starts the
     * transcription flow both answers end in.
     */
    fun choose(kind: ImportedRecording.Kind) {
        val uri = source ?: return
        if (hasChosen) return
        hasChosen = true
        _state.value = State.Importing
        viewModelScope.launch {
            when (val outcome = AudioImport.import(getApplication(), uri, kind)) {
                is AudioImport.Outcome.Refused -> _state.value = State.ImportRefused(outcome.reason)
                is AudioImport.Outcome.Imported -> afterImport(outcome, kind)
            }
        }
    }

    /**
     * What happens between a finished copy and a queued transcription.
     *
     * The order mirrors the in-app path's exactly, and the order is the point: refuse a file that is
     * too long before anything else (the decode would exhaust the heap after a long wait, producing
     * nothing), then say so if there is no model (the worker retries for ever while one is missing,
     * so a tap would appear to do nothing at all), and only then ask questions.
     *
     * **Both refusals keep the audio.** For a kept import that is obvious — it is in Recordings. For
     * a transcribe-only one it matters more: it is deliberately not in Recordings, and it is the
     * Transcripts page's *waiting* group that stops it being a file in no list at all.
     */
    private suspend fun afterImport(
        imported: AudioImport.Outcome.Imported,
        kind: ImportedRecording.Kind,
    ) {
        val context = getApplication<Application>()
        val label = ImportedRecording.labelOf(imported.displayName) ?: imported.displayName

        val isModelInstalled =
            withContext(Dispatchers.IO) { ModelRepository.isInstalled(context, modelOf(context)) }
        importedDurationMs = imported.durationMs
        importedKind = kind
        _state.value = when (val step = ImportFollowUp.after(imported.durationMs, isModelInstalled)) {
            is ImportFollowUp.Step.TooLong -> {
                AppLogger.w(TAG, "Imported '${imported.displayName}' is too long to transcribe")
                State.TooLong(step.minutes)
            }
            ImportFollowUp.Step.NoModel -> {
                AppLogger.w(TAG, "Imported '${imported.displayName}' but no model is installed")
                State.NoModel
            }
            ImportFollowUp.Step.AskLanguage -> State.AskLanguage(imported.displayName, label)
        }
    }

    /** The copy's length, carried from the import to the estimate rather than read a second time. */
    private var importedDurationMs: Long = 0L

    /** What the user chose, carried so the finished card can promise the right thing. */
    private var importedKind: ImportedRecording.Kind = ImportedRecording.Kind.KEEP

    /** The language has been picked; move on to the estimate. */
    fun languageChosen(displayName: String, label: String, language: String?) {
        val context = getApplication<Application>()
        val model = modelOf(context)
        val prefs = AppPreferences(context)
        val estimate = if (importedDurationMs <= 0L) null else {
            TranscriptionEstimate.estimateMs(audioMs = importedDurationMs, cost = prefs.getRunCost(model))
        }
        _state.value = State.Confirm(
            displayName = displayName,
            label = label,
            estimateMs = estimate,
            // Before this phone has timed a run the figure comes from published numbers for other
            // hardware — right to within a factor of two or three, which is not a promise worth
            // making to someone about to watch a progress bar (issue #26).
            isFirstRun = !prefs.hasMeasuredRun(model.id),
            language = language,
        )
    }

    /** The estimate is accepted; queue the run. */
    fun confirmed(displayName: String, language: String?, dontAskAgain: Boolean) {
        val context = getApplication<Application>()
        if (dontAskAgain) AppPreferences(context).setTranscriptionConfirmBeforeRun(false)
        val kind = importedKind
        _state.value = State.Queued(displayName, kind)
        viewModelScope.launch {
            // retry() rather than a bare enqueue: it clears any row first, so the same call serves a
            // first transcription and a retry alike, and the queue skips a row it has already seen.
            TranscriptRepository.retry(context, displayName, language)
            AppLogger.i(TAG, "Queued '$displayName' ($kind)")
        }
    }

    /**
     * The user backed out of the language or estimate dialog.
     *
     * The file stays imported. Cancelling the question is not cancelling the import — the copy has
     * already happened, and deleting it here would mean a share that asked two questions and then
     * silently threw the answer away. A kept import is in Recordings; a transcribe-only one is in
     * the Transcripts page's waiting group, with Transcribe one tap away.
     */
    fun transcriptionDeclined(displayName: String) {
        AppLogger.i(TAG, "Transcription of '$displayName' was not started; the audio is kept")
        _state.value = State.NotTranscribed(displayName, importedKind)
    }

    private fun modelOf(context: Context): TranscriptionModel =
        TranscriptionModel.fromId(AppPreferences(context).getTranscriptionModelId())
            ?: TranscriptionModel.DEFAULT

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
