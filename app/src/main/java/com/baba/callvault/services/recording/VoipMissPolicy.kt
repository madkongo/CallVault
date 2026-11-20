/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.data.health.Prerequisite
import com.baba.callvault.system.storage.SafHelper
import com.baba.callvault.system.storage.StagingNote
import java.io.File

/** What to say about an app call that went unrecorded. */
enum class MissReport {
    /** Nothing. Not every miss is worth a warning. */
    SILENT,

    /** The user's own setup explains it — an excused miss, blamed on the setting at fault. */
    EXCUSED,

    /** Nothing explains it. This is the one worth chasing. */
    UNEXPLAINED
}

/**
 * Whether a VoIP call nobody recorded is worth telling the user about.
 *
 * Separated from [VoipRecordingCoordinator] so the rule can be tested without a phone, a daemon, or
 * a call — the same shape as [VoipTelephonyGate] and [RecordingPolicy]. The decision is small and
 * the cost of getting it wrong is asymmetric in both directions, which is exactly when a rule
 * deserves to be written down on its own.
 */
object VoipMissPolicy {

    /**
     * @param everRecordedSuccessfully whether ANY call has ever been recorded on this setup.
     * @param missingPrerequisite the user-owned setting at fault, where there is one.
     */
    fun report(everRecordedSuccessfully: Boolean, missingPrerequisite: Prerequisite?): MissReport = when {
        // A setup still being put together records nothing *by definition*, so a warning here reports
        // the very thing the user is in the middle of fixing. The status card already says setup is
        // incomplete; a notification saying the same thing in an alarming voice only teaches people
        // that CallVault's warnings can be ignored — which is expensive later, when one is real.
        !everRecordedSuccessfully -> MissReport.SILENT

        // Blamed on the setting rather than on the app. Kept apart from UNEXPLAINED deliberately: one
        // says "fix your folder", the other says "CallVault failed and we do not know why", and a
        // user who cannot tell those apart will act on neither.
        missingPrerequisite != null -> MissReport.EXCUSED

        else -> MissReport.UNEXPLAINED
    }

    /**
     * Whether to tell the user about an app call whose capture never started in the recorder (review N21).
     *
     * Only when the recorder said why ([startFailureReason], from `VoipStartFailure`): an empty capture with no
     * reason is as likely a call that ended before the session was built, or a recorder that died, and gets the
     * always-on log line alone. And not for a call under [VoipStubPolicy.TOO_SHORT_TO_JUDGE_MS], the length below
     * which an app call is a misdial or a ring as far as every other rule here is concerned. The phone's own call
     * evidence ([CallEvidence]) cannot be asked: at the end the call is already over.
     *
     * @param recordedMs how long the recording was meant to have been running; null when unknown, which is told.
     */
    fun tellStartFailure(startFailureReason: String?, recordedMs: Long?): Boolean =
        startFailureReason != null && (recordedMs == null || recordedMs >= VoipStubPolicy.TOO_SHORT_TO_JUDGE_MS)

    /** What a start that found no recorder comes to, once its call has ended (review N18). */
    enum class NotReadyVerdict {
        /** Nothing recorded the call: say so. */
        REPORT,

        /** A recording of the call exists: the recorder's session for a killed CallVault, staged or in the library. */
        RECORDED_ELSEWHERE,

        /** The library could not be read, so a rescued recording of the call cannot be ruled out: not reported. */
        UNVERIFIABLE,

        /** Nothing on the phone looked like a call when it started (issue #29): not a miss. */
        NOT_A_CALL,
    }

    /**
     * Whether an app call whose start found the recorder not connected went unrecorded (review N18).
     *
     * Not decided at the start: a CallVault restarted into a call (the phone killed it mid-call, Q25) starts that call
     * before its recorder connection is back, while the recorder is still recording the call for the killed process.
     * Saying "not recorded" then was false, and the rescue published the whole call. At the end it is known.
     *
     * @param lookedLikeCallAtStart [CallEvidence] at the start; at the end the call is over and it says nothing.
     * @param recorderRecordingUnowned the recorder is recording a session this process does not own; null when the
     *   recorder could not be asked (still not connected).
     * @param foundElsewhere a recording of the call was found staged or in the library; null when the library could
     *   not be read and nothing else was found.
     */
    fun notReadyAtCallEnd(lookedLikeCallAtStart: Boolean, recorderRecordingUnowned: Boolean?, foundElsewhere: Boolean?): NotReadyVerdict = when {
        recorderRecordingUnowned == true || foundElsewhere == true -> NotReadyVerdict.RECORDED_ELSEWHERE
        !lookedLikeCallAtStart -> NotReadyVerdict.NOT_A_CALL
        foundElsewhere == null -> NotReadyVerdict.UNVERIFIABLE
        else -> NotReadyVerdict.REPORT
    }

    /**
     * The staged recording of a call that ran from [sinceMillis] to [untilMillis], if [files] (the staging folder)
     * hold one: non-empty, written at or after [sinceMillis], started (by its note) no later than [untilMillis], and
     * not claimed by [ownedLive] for a recording still running here. A recording started after the call ended is the
     * next call's (a phone call answered over it), never this one's.
     */
    fun stagedRecordingOfCall(files: List<File>, sinceMillis: Long, untilMillis: Long, ownedLive: (File) -> Boolean): File? =
        files.firstOrNull { f ->
            SafHelper.isStagedRecording(f) && f.length() > 0L && f.lastModified() >= sinceMillis && !ownedLive(f) &&
                (startedAt(f) ?: f.lastModified()) <= untilMillis
        }

    private fun startedAt(staged: File): Long? = runCatching {
        StagingNote.fileFor(staged).takeIf { it.exists() }?.let { StagingNote.decode(it.readText())?.startedAtMillis }
    }.getOrNull()

    /**
     * The catalogued recording (name, start) whose start lies within [fromMillis]..[untilMillis], if any: the killed
     * CallVault's recording, published by the cut-off rescue before the call ended.
     */
    fun cataloguedOfCall(entries: List<Pair<String, Long?>>, fromMillis: Long, untilMillis: Long): String? =
        entries.firstOrNull { (_, start) -> start != null && start in fromMillis..untilMillis }?.first
}
