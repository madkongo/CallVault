/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

/**
 * What the shade should say once one more transcription has ended.
 *
 * **The whole reason this is a merge rather than a post.** Transcription runs in batches — the nightly
 * sweep takes up to fifty recordings in one go — and a notification per recording would be fifty lines
 * in the shade for work the user never watched. Nor is "notify only for a run the user started by
 * hand" enough on its own: the case the maintainer actually reported is a *shared* voice note, where
 * the user has left CallVault entirely and the app's own screen is the one place they are not looking.
 *
 * So there is one notification, and each finish folds into it: the first names the recording, and
 * every one after that turns it into a count. Ten transcripts are one line that reads "10 transcripts
 * ready", whoever asked for them and however they were queued.
 *
 * [previous] is read back off the notification that is still in the shade rather than stored, which
 * makes dismissing it the reset — and that is the right reset. A user who swiped the last batch away
 * has seen it; the next transcript is fresh news and deserves to be named again.
 */
object TranscriptNotice {

    /**
     * How one recording's transcription ended, as far as the user needs telling.
     *
     * Only the three endings worth a word. A stop, an abort and a refusal for length are not here on
     * purpose: a stop is the user's own doing and needs no report, and a refusal already raises a
     * dialog in front of whoever asked.
     */
    enum class Outcome {

        /** The words are stored and the recording is where it was. */
        Stored,

        /**
         * The words are stored and the audio is gone: a "Transcribe only" import, whose file is
         * deleted as the transcript lands. The one case where the notification is not a convenience
         * but the app's only account of what became of a file.
         */
        StoredAndAudioDeleted,

        /** The run ended in failure and the recording still has no transcript. */
        Failed,
    }

    /**
     * One notification's worth of finished work.
     *
     * @param count       how many finishes this notification now stands for; at least 1.
     * @param displayName the single recording it names, or null once it stands for more than one —
     *                    because a notification that named the last of ten would send the user to a
     *                    transcript they never asked about while saying nothing about the other nine.
     * @param audioDeleted true when that one recording's audio was removed as the transcript was
     *                    stored (a "Transcribe only" import). Meaningless, and therefore false, once
     *                    [displayName] is null: the sentence it drives is about one file.
     */
    data class Shade(
        val count: Int,
        val displayName: String?,
        val audioDeleted: Boolean,
    )

    /**
     * Folds one more finish into whatever the shade is already showing.
     *
     * @param previous what is in the shade now, or null when nothing of ours is.
     * @param displayName the recording that has just finished.
     * @param audioDeleted whether storing its transcript deleted its audio.
     */
    fun after(previous: Shade?, displayName: String, audioDeleted: Boolean = false): Shade {
        if (previous == null) {
            return Shade(count = 1, displayName = displayName, audioDeleted = audioDeleted)
        }
        // A count of two or more names nothing and says nothing about deleted audio: both are
        // statements about one file, and repeating the last one's would be a sentence about the
        // wrong recording — worse than no sentence, because it reads as being about all of them.
        return Shade(count = previous.count + 1, displayName = null, audioDeleted = false)
    }
}
