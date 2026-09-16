/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which Intents the share target acts on, and what the others are told.
 *
 * The share target is `exported="true"` — it has to be, or the share sheet cannot reach it — so
 * every Intent shape here is something any app on the phone, or `adb shell am start`, can send. The
 * cases below are the ones that must not end in a crash, a spinner that never resolves, or a file
 * written into someone's recordings folder before the app is set up to hold one.
 */
class SharedAudioTest {

    private fun reasonFor(
        action: String? = "android.intent.action.SEND",
        hasStream: Boolean = true,
        isAppSetUp: Boolean = true,
    ): SharedAudio.Reason? =
        when (val verdict = SharedAudio.verdictFor(action, hasStream, isAppSetUp)) {
            is SharedAudio.Verdict.Refuse -> verdict.reason
            SharedAudio.Verdict.Import -> null
        }

    @Test
    fun `a share carrying a file on a set-up app is imported`() {
        assertEquals(null, reasonFor())
    }

    @Test
    fun `a share of text with no file is refused as nothing shared`() {
        // Several apps offer "share" on a message and send its TEXT, with no EXTRA_STREAM at all.
        assertEquals(SharedAudio.Reason.NOTHING_SHARED, reasonFor(hasStream = false))
    }

    @Test
    fun `a multiple-file share is refused rather than half-handled`() {
        // Not in the manifest, so it cannot arrive through the share sheet — but an explicit
        // component can send it, and taking the first of five files silently would be worse than
        // saying we take one at a time. Batch import is its own piece of work.
        assertEquals(
            SharedAudio.Reason.NOT_A_SHARE,
            reasonFor(action = "android.intent.action.SEND_MULTIPLE"),
        )
    }

    @Test
    fun `a launch with no action at all is refused`() {
        assertEquals(SharedAudio.Reason.NOT_A_SHARE, reasonFor(action = null))
    }

    @Test
    fun `a view intent is refused rather than treated as a share`() {
        assertEquals(SharedAudio.Reason.NOT_A_SHARE, reasonFor(action = "android.intent.action.VIEW"))
    }

    @Test
    fun `a share arriving before setup has finished says so, and is not imported`() {
        assertEquals(SharedAudio.Reason.NOT_SET_UP, reasonFor(isAppSetUp = false))
    }

    @Test
    fun `what is wrong with the share is said before what is wrong with the app`() {
        // Both are wrong here. Telling someone to finish setting the app up, when what they shared
        // carried no file, sends them through a wizard that will not fix it.
        assertEquals(
            SharedAudio.Reason.NOTHING_SHARED,
            reasonFor(hasStream = false, isAppSetUp = false),
        )
    }

    @Test
    fun `the action we match is spelled the way the system spells it`() {
        // The manifest matches the literal string; this asserts the constant the code compares
        // against is that same string, so a share sheet entry can never point at a target that
        // refuses everything it is given.
        assertEquals(null, reasonFor(action = android.content.Intent.ACTION_SEND))
    }
}
