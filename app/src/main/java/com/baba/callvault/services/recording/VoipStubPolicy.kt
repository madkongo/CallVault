/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

/**
 * A VoIP recording too short to say anything about, because the call ended before the capture got
 * going (#68).
 *
 * Starting takes a second or more (the daemon, the file, the app lookup). A call that ends inside that
 * window leaves a fraction of a second of our own mic, with the far side never heard. That is not the
 * other app blocking capture, so warning about it blames the wrong thing; and there is no call in it
 * worth keeping. A long one-sided recording is still the real warning.
 */
object VoipStubPolicy {

    /** Under this much audio with the far side never heard, the call was over before the capture began. */
    const val TOO_SHORT_TO_JUDGE_MS: Long = 3_000L

    /**
     * Whether to drop the recording quietly. [recordedMs] is the audio written (null if the clock never
     * started); [farHeard] says whether the other party was ever audible.
     */
    fun endedBeforeCapture(recordedMs: Long?, farHeard: Boolean): Boolean =
        !farHeard && recordedMs != null && recordedMs < TOO_SHORT_TO_JUDGE_MS
}
