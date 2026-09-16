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
}
