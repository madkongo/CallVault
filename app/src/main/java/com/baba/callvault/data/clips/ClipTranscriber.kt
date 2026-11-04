/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.clips

import android.content.Context
import android.net.Uri
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.transcription.DecodeSettings
import com.baba.callvault.transcription.TranscriptionEngine
import com.baba.callvault.transcription.TranscriptionEngineChoice
import com.baba.callvault.transcription.TranscriptionLanguageChoice
import com.baba.callvault.transcription.model.TranscriptionModel
import com.baba.callvault.utils.AppLogger
import kotlin.coroutines.cancellation.CancellationException

/**
 * "Transcribe just this part": transcribes the clip file itself with whatever engine the user set up (on this phone
 * or their own cloud key), and returns its lines on the call's clock. Nothing is stored — a transcript of a piece of
 * a call is not the call's transcript.
 */
object ClipTranscriber {

    private const val TAG = "CV:ClipTranscriber"

    sealed interface Outcome {
        data class Done(val lines: List<ClipLine>) : Outcome

        /** The on-device engine is already transcribing something; it runs one job at a time. */
        data object Busy : Outcome

        /** No model downloaded, or the chosen cloud service has no key. */
        data object NotSetUp : Outcome

        data class Failed(val message: String) : Outcome
    }

    /** 0–100 while [transcribe] runs; 0 when unknown. */
    @Volatile var progressPercent: () -> Int = { 0 }
        private set

    suspend fun transcribe(context: Context, clip: ClipCutter.Result, range: ClipRange): Outcome {
        val prefs = AppPreferences(context)
        val model = TranscriptionModel.fromId(prefs.getTranscriptionModelId()) ?: TranscriptionModel.DEFAULT
        val engine = when (val chosen = TranscriptionEngineChoice.choose(context, prefs, model, isNamedRun = true)) {
            is TranscriptionEngineChoice.Run -> chosen
            TranscriptionEngineChoice.WaitForModel, TranscriptionEngineChoice.CloudNotSetUp -> return Outcome.NotSetUp
        }
        if (!engine.isCloud && TranscriptionEngine.isRunning) return Outcome.Busy
        progressPercent = engine.progressPercent
        val language = TranscriptionLanguageChoice.resolve(chosen = null, setting = prefs.getTranscriptionLanguage())
        val uri = Uri.fromFile(clip.file)
        return try {
            val segments = engine.transcriber
                ?.transcribe(context, uri, engine.modelPath, language, null, null, DecodeSettings.DEFAULT)
                ?: TranscriptionEngine.transcribe(context, uri, engine.modelPath, language)
            val lines = segments.map { ClipLine(it.startMs, it.endMs, it.text) }
            AppLogger.i(TAG, "Clip transcribed: ${lines.size} lines")
            Outcome.Done(ClipTranscript.fromClipFile(lines, range, clip.leadInMs))
        } catch (e: CancellationException) {
            // Deliberately no TranscriptionEngine.requestAbort(): that flag is global, and a queued transcription of a
            // real call may be the run it reaches (review 2026-11-04). The engine runs one job at a time on its own
            // thread, so a cancelled clip run just finishes there and its result is dropped — a few seconds of CPU.
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "Clip transcription failed: ${e.message}")
            Outcome.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            progressPercent = { 0 }
        }
    }
}
