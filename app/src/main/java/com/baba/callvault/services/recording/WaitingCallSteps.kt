/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.content.Context
import android.net.Uri
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.calls.PhoneCallMatch
import com.baba.callvault.data.calls.PhoneCallReader
import com.baba.callvault.data.calls.SimLabels
import com.baba.callvault.data.recordings.RecordingDirection
import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.data.transcripts.FlagRepository
import com.baba.callvault.data.transcripts.SpeakerTurnsRepository
import com.baba.callvault.system.LocalizedContext
import com.baba.callvault.system.diagnostics.CallEndLog
import com.baba.callvault.system.interop.MetadataSidecar
import com.baba.callvault.system.storage.LiveStagingFiles
import com.baba.callvault.system.storage.MinDurationPolicy
import com.baba.callvault.system.storage.StagingNote
import com.baba.callvault.system.storage.WaitingNote
import com.baba.callvault.transcription.AudioDecoder
import com.baba.callvault.utils.AppLogger
import java.io.File

/**
 * The after-call steps of a call kept in waiting storage (review 2026-11-20: handled like a normal call), built from the
 * same parts the normal paths use. The normal paths themselves are left as they are, so a phone whose folder works
 * runs exactly the code it ran before; these are the same calls, in the same order, on a file instead of a document.
 *
 * Split by when the data exists: [atCallEnd] collects what only exists right after the call (the marks, the speaker
 * turns the recorder measured) and applies the minimum-duration setting before anyone is told; [writeDetails] writes the
 * details file when the call is saved into the folder, which did not exist at the call's end.
 */
object WaitingCallSteps {

    private const val TAG = "CV:WaitingSteps"

    /**
     * Run on a background thread right after the call. Returns false when the call was under the minimum-duration
     * setting and was discarded (nothing is told: the user asked not to hear about misdials, as on the normal path).
     *
     * @param outgoingForTurns what the normal path passes to [SpeakerTurnsRepository.collectAfterCall]: true only for an
     *   outgoing carrier call recorded from the call source.
     */
    suspend fun atCallEnd(context: Context, file: File, name: String, path: String, outgoingForTurns: Boolean): Boolean {
        val minSeconds = AppPreferences(context).getMinDurationSeconds()
        if (minSeconds > 0) {
            val durationMs = CallEndLog.timedProbe(context, path, name) { AudioDecoder.durationMs(context, Uri.fromFile(file)) }
            if (MinDurationPolicy.shouldDiscard(durationMs, minSeconds)) {
                AppLogger.i(TAG, "Discarding the waiting '$name': ${durationMs}ms is under the ${minSeconds}s minimum.")
                LiveStagingFiles.release(file)
                StagingNote.deleteWithNote(file)
                CallEndLog.record(context, "$path waiting recording under the minimum duration (${durationMs} ms); discarded")
                return false
            }
        }
        // Keyed to the final name, as on the normal paths: these exist only now, and are found by name when it is saved.
        runCatching { FlagRepository.save(context, name, PendingFlags.drain()) }
            .onFailure { AppLogger.w(TAG, "Could not keep the marks of '$name': ${it.message}") }
        runCatching { SpeakerTurnsRepository.collectAfterCall(context, name, outgoing = outgoingForTurns) }
            .onFailure { AppLogger.w(TAG, "Could not collect the speaker turns of '$name': ${it.message}") }
        return true
    }

    /** The details (.json) file, when the user asked for one, written as the normal paths write it. */
    suspend fun writeDetails(context: Context, name: String, waiting: WaitingNote) {
        val parsed = RecordingsRepository.parseName(name)
        val extras = waiting.extras
        val isVoip = extras?.isVoip == true
        val startedAt = parsed.startedAtMillis ?: waiting.note.startedAtMillis
        MetadataSidecar.writeIfEnabled(
            context = context,
            folderUri = AppPreferences(context).getRecordingFolderUri(),
            audioName = name,
            timestampUnixMs = startedAt,
            packageName = if (isVoip) null else "com.android.phone",
            direction = if (isVoip) extras?.direction else when (parsed.direction) {
                RecordingDirection.INCOMING -> "in"
                RecordingDirection.OUTGOING -> "out"
                else -> null
            },
            phoneNumber = parsed.number,
            contactName = parsed.contactName,
            durationSecsEncoded = null,
            // An app call, or a Wi-Fi call recorded as one, puts the near side on the left by construction.
            nearChannel = if (isVoip || extras?.appCallMix == true) "A" else SpeakerTurnsRepository.trustedMap(context).nearChannelKey(),
            simSlot = if (isVoip) null else simSlotFor(context, startedAt),
        )
    }

    /** The 1-based SIM slot of the call that started at [startedAt], when the opt-in SIM label is on; as the carrier path. */
    private fun simSlotFor(context: Context, startedAt: Long): Int? = runCatching {
        if (!AppPreferences(context).isShowSimLabelEnabled()) return@runCatching null
        val calls = PhoneCallReader.since(context, startedAt - PhoneCallMatch.TOLERANCE_MS)
        val call = PhoneCallMatch.nearest(calls, startedAt) ?: return@runCatching null
        SimLabels.Resolver(context, LocalizedContext.of(context).getString(R.string.sim_label_word))
            .simFor(call.accountComponent, call.accountId)?.slot
    }.getOrNull()
}
