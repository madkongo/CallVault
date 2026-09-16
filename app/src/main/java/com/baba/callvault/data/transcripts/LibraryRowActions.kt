/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

import com.baba.callvault.data.transcripts.db.TranscriptState
import com.baba.callvault.data.transcripts.export.TranscriptFormat

/**
 * What a row on the Transcripts or Summaries page offers, and what its Delete destroys.
 *
 * Pure, for the reason [TranscriptsPage] is: every wrong answer here still renders perfectly. A
 * menu offering Share on a row with nothing to share is a tap that does nothing; a Delete whose
 * confirmation describes the wrong thing is a user destroying something they were told was safe.
 * Neither crashes, neither logs, and the only way to find out is to press it.
 *
 * ## What Delete means here, which is the decision worth stating
 *
 * **On the Transcripts page Delete removes the text, never the recording.** That is what the page
 * lists, and the recording stays reachable from Recordings, where deleting one already lives behind
 * a confirmation that names the copies it would take. A list of *words* that quietly destroys
 * *audio* is the shape of the mistake nobody forgives — so it is not offered at all, in either
 * direction, rather than offered carefully.
 *
 * The exception is the row where the two are the same thing: a transcript whose recording is gone —
 * every finished "Transcribe only" import, and any transcript that outlived its recording. Deleting
 * its text is deleting the whole item, so [DeleteMeaning.TranscriptAndNothingLeft] exists to make
 * the confirmation say so in words rather than repeating the reassuring sentence about a recording
 * that is not there.
 *
 * **On the Summaries page Delete removes the summary.** The transcript it was written from and the
 * recording both stay, and the summary can be written again from the row's own reading view.
 */
object LibraryRowActions {

    /** Which page a row belongs to. The two differ in what Delete means and what Save can write. */
    enum class Page { Transcripts, Summaries }

    /** What a confirmed Delete removes — and therefore what its confirmation has to say. */
    enum class DeleteMeaning {

        /** The words. The recording stays, and Recordings is where it still is. */
        TranscriptKeepingAudio,

        /** The words are the whole item: there is no recording behind them to keep. */
        TranscriptAndNothingLeft,

        /** The summary. The transcript it was written from, and the recording, both stay. */
        Summary,
    }

    /**
     * What one row's overflow menu offers.
     *
     * @param share the text this page is about — the transcript's words, or the summary.
     * @param save  the same text as a file, in one of [formatsFor]'s formats.
     * @param delete null where a row has nothing of its own to remove.
     */
    data class Menu(
        val share: Boolean,
        val save: Boolean,
        val delete: DeleteMeaning?,
    ) {
        /** Nothing to offer, so no menu button is drawn: three dots that open an empty sheet are worse than none. */
        val isEmpty: Boolean get() = !share && !save && delete == null
    }

    /** No menu at all. */
    val NONE = Menu(share = false, save = false, delete = null)

    /**
     * A Transcripts row's menu.
     *
     * **Only a finished transcript gets one.** A queued or running row has no words yet, a failed
     * one never produced any, and a file merely *waiting* to be transcribed has none either — so
     * Share and Save would be two taps that do nothing on three of the page's four groups. Those
     * rows already carry the one action they need in their trailing slot (transcribe, or retry), and
     * the queue's own Stop is what cancels a run; a second way to make a running transcription
     * disappear, spelled "Delete", would be the confusing one.
     *
     * @param hasAudio true when the recordings catalog still holds a row for this transcript. It
     *   changes only what Delete *means*, never whether it is offered: the text is deletable either
     *   way, and the difference is the sentence the user is asked to agree to.
     */
    fun forTranscript(state: TranscriptState, hasAudio: Boolean): Menu =
        if (state != TranscriptState.DONE) NONE
        else Menu(
            share = true,
            save = true,
            delete = if (hasAudio) DeleteMeaning.TranscriptKeepingAudio
                     else DeleteMeaning.TranscriptAndNothingLeft,
        )

    /**
     * A Summaries row's menu.
     *
     * Offered on a stored summary and on nothing else. A summary being written has no row in the
     * database yet — the asymmetry [SummariesPage] is built around — so there is nothing to share,
     * nothing to save and nothing to delete until it lands, and Stop is already on the heading above.
     */
    fun forStoredSummary(): Menu = Menu(share = true, save = true, delete = DeleteMeaning.Summary)

    /**
     * Which file formats Save offers on [page].
     *
     * Transcripts get all five, exactly as the reading view does — the same menu in the same order,
     * because a row and the page it opens writing different files would be two answers to one
     * question.
     *
     * Summaries get the two that have somewhere to put a summary. [TranscriptFormat.SRT] and
     * [TranscriptFormat.VTT] are subtitle files, which carry timed lines of speech and nothing else;
     * [TranscriptFormat.TXT] is the transcript as it reads on screen, which for a summary row would
     * write a file with no summary in it at all. Offering a format that silently drops the thing
     * being saved is the defect this list exists to prevent.
     */
    fun formatsFor(page: Page): List<TranscriptFormat> = when (page) {
        Page.Transcripts -> TranscriptFormat.entries.toList()
        Page.Summaries -> listOf(TranscriptFormat.MARKDOWN, TranscriptFormat.JSON)
    }

    /** Which sentence a bulk delete asks, over a selection that may be of mixed kinds. */
    enum class BulkPrompt {

        /** Transcripts, every one of which still has its recording to be transcribed again from. */
        TranscriptsAudioKept,

        /** Transcripts, at least one of which is the only record left of what was said. */
        TranscriptsSomeAllThatIsLeft,

        /** Summaries. The transcripts and the recordings all stay, whatever is in the selection. */
        Summaries,
    }

    /**
     * What a bulk delete over a selection should say before it runs.
     *
     * **A mixed selection is decided by its worst case**, not by its majority: one transcript with
     * no recording behind it is enough to make the reassuring sentence — "the recordings are kept,
     * you can transcribe them again" — false for something in the batch. The user is told how many,
     * so "one of forty" and "forty of forty" are not the same warning.
     *
     * @param textOnlyCount how many of the selected rows have no recording in the catalog. Always
     *   zero for [Page.Summaries], where it would change nothing: deleting a summary never touches
     *   audio, so whether the audio is there is not part of what is being agreed to.
     */
    fun bulkPrompt(page: Page, textOnlyCount: Int): BulkPrompt = when {
        page == Page.Summaries -> BulkPrompt.Summaries
        textOnlyCount > 0 -> BulkPrompt.TranscriptsSomeAllThatIsLeft
        else -> BulkPrompt.TranscriptsAudioKept
    }
}
