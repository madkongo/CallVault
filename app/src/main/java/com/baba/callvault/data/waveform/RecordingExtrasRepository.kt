/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.waveform

import android.content.Context
import android.net.Uri
import com.baba.callvault.data.transcripts.db.RecordingNoteEntry
import com.baba.callvault.data.transcripts.db.RecordingWaveformEntry
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.system.interop.TranscriptSidecar
import com.baba.callvault.transcription.AudioDecoder
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The two things attached to a recording that are neither the audio nor its transcript: what the user
 * wrote about it, and the shape used to draw it.
 */
object RecordingExtrasRepository {

    private const val TAG = "CV:RecordingExtras"

    private fun dao(context: Context) = TranscriptDatabase.get(context).noteDao()

    /** The note for [displayName], empty when there is none. */
    fun note(context: Context, displayName: String): Flow<String> {
        // Do not materialise a database just to find out a recording has no note — most never will.
        if (!TranscriptDatabase.exists(context)) return flowOf("")
        return dao(context).observeNote(displayName).map { it?.text.orEmpty() }
    }

    /** Saves [text], or removes the note when it has been emptied. */
    suspend fun saveNote(context: Context, displayName: String, text: String) {
        val dao = dao(context)
        if (text.isBlank()) {
            dao.deleteNote(displayName)
        } else {
            dao.upsertNote(
                RecordingNoteEntry(
                    displayName = displayName,
                    text = text,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        // Keep the beside-the-audio transcript/notes file in step with the edited note (no-op when the
        // sidecar setting is off; never throws).
        TranscriptSidecar.writeOrClear(context, displayName)
    }

    /**
     * The recording's drawn shape, computing and caching it the first time.
     *
     * Decoding is the expensive part — about twelve seconds for a ninety-minute call — so it happens
     * once and the result is stored. Returns empty when the audio cannot be read, which the drawing
     * treats as "no waveform" rather than as an error worth interrupting playback for.
     */
    suspend fun waveform(context: Context, displayName: String, uri: Uri): FloatArray =
        withContext(Dispatchers.IO) {
            val dao = dao(context)

            dao.waveform(displayName)?.let { cached ->
                val peaks = Waveform.decode(cached)
                if (peaks.isNotEmpty()) return@withContext peaks
            }

            val peaks = runCatching {
                Waveform.reduce(AudioDecoder.decodeToMono16k(context, uri), Waveform.BUCKETS)
            }.getOrElse {
                AppLogger.w(TAG, "Could not draw $displayName: ${it.message}")
                return@withContext FloatArray(0)
            }

            runCatching {
                dao.upsertWaveform(
                    RecordingWaveformEntry(
                        displayName = displayName,
                        peaks = Waveform.encode(peaks),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }.onFailure { AppLogger.w(TAG, "Could not cache the shape of $displayName: ${it.message}") }

            peaks
        }

    /**
     * Computes and caches the shape now, so opening the recording later has nothing to wait for.
     *
     * Called when a call finishes. Reading the audio is the slow part — about twelve seconds for a
     * ninety-minute call — and doing it here spends that on a phone that has just come off a call
     * rather than on a user who has just tapped a recording and is looking at the screen.
     *
     * Skips anything already cached, so it costs nothing when called twice.
     */
    suspend fun precomputeWaveform(context: Context, displayName: String, uri: Uri) {
        runCatching {
            if (dao(context).waveform(displayName) != null) return
            waveform(context, displayName, uri)
            AppLogger.i(TAG, "Drew $displayName ahead of time")
        }.onFailure { AppLogger.w(TAG, "Could not pre-draw $displayName: ${it.message}") }
    }

    /**
     * Removes both when a recording goes.
     *
     * The note matters here for the same reason the transcript does: it is about a private call, and
     * leaving it behind would keep a written record of a conversation the user deleted.
     */
    suspend fun deleteFor(context: Context, displayName: String) {
        if (!TranscriptDatabase.exists(context)) return
        val dao = dao(context)
        runCatching {
            dao.deleteNote(displayName)
            dao.deleteWaveform(displayName)
        }.onFailure { AppLogger.w(TAG, "Could not clear extras for $displayName: ${it.message}") }
    }
}
