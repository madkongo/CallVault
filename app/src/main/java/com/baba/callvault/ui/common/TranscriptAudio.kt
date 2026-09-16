/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

/**
 * What the app may offer for a transcript, given whether its recording is still there.
 *
 * **A transcript outliving its audio is ordinary, not exotic.** Every finished "Transcribe only"
 * import is one by design, and any transcript whose recording was deleted is another — the two are
 * separate databases and the cascade is called by hand. Until now the reading view showed such a
 * transcript the full transport anyway: play, skip, a position that never moved, and lines that
 * invited a tap and answered nothing. Four dead controls, with nothing on screen to say why.
 *
 * The question is answered from the catalog, not from a new column: the reading view already looks
 * the recording up to find its Uri, and a row that is absent is exactly what "the audio is gone"
 * means. A stored flag would be a second source for the same fact and would go stale the first time
 * a recording was deleted by any of the several paths that delete one.
 */
object TranscriptAudio {

    /**
     * Whether the reading view draws the transport and lets a line be tapped to play from it.
     *
     * @param hasAudio true when the recordings catalog still holds a row for this transcript.
     */
    fun playbackOffered(hasAudio: Boolean): Boolean = hasAudio

    /**
     * Whether the reading view offers "Transcribe again".
     *
     * **There is nothing to transcribe from.** Re-running the model means decoding the recording,
     * and for a transcript whose audio is gone the button can only throw the stored text away and
     * queue a run that refuses itself — which is the one outcome nobody would press it for. It sat
     * there after the transport was taken away, which is the same defect one control later: a live
     * button in a row of live buttons, with nothing on screen to say it cannot work.
     *
     * The rest of the actions row is text and stays: Share and its five export formats, the note,
     * the speaker-name swap and Delete all read and write the stored words, never the file. So does
     * the summary strip above them — [com.baba.callvault.summary.SummaryQueue.blockerFor] asks only
     * for a finished transcript — which is why summarising a text-only transcript is still offered.
     *
     * @param hasAudio true when the recordings catalog still holds a row for this transcript.
     */
    fun retranscribeOffered(hasAudio: Boolean): Boolean = hasAudio

    /** What a library row puts beside its title. */
    enum class RowBadge {

        /** A recorded call that still has its audio. Nothing to say; the row is the ordinary kind. */
        None,

        /** A file the user brought in, still on the phone. */
        Imported,

        /** The words are all that is left: the recording behind them is gone. */
        TextOnly,
    }

    /**
     * Which badge a Transcripts or Summaries row carries.
     *
     * **One badge, never two**, which is the maintainer's own rule and also the right one: the row's
     * trailing slot is fixed and its title already ellipsises, so a second pill would eat the name.
     * Where both could apply, "Text only" wins — it is the fact that changes what the row can *do*
     * (no player, no tap-to-play), while where the file came from is history. An import whose audio
     * is gone is still visibly an import from its name, which is what the row is titled with.
     *
     * @param hasAudio true when the recordings catalog still holds a row for this name.
     * @param isImported read from the NAME rather than from a catalog row, because an import that has
     *   been deleted is still an import — see [com.baba.callvault.data.recordings.ImportedRecording].
     */
    fun badgeFor(hasAudio: Boolean, isImported: Boolean): RowBadge = when {
        !hasAudio -> RowBadge.TextOnly
        isImported -> RowBadge.Imported
        else -> RowBadge.None
    }
}
