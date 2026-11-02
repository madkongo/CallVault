/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #68: a call that ended before the capture got going is dropped, not reported as one-sided. */
class VoipStubPolicyTest {

    @Test
    fun `a 0_7 s recording with no far side is dropped quietly`() {
        assertTrue(VoipStubPolicy.endedBeforeCapture(700L, farHeard = false))
    }

    @Test
    fun `a long recording with no far side is still the one-sided warning`() {
        assertFalse(VoipStubPolicy.endedBeforeCapture(45_000L, farHeard = false))
    }

    @Test
    fun `a short recording where the far side was heard is kept`() {
        assertFalse(VoipStubPolicy.endedBeforeCapture(1_500L, farHeard = true))
    }

    @Test
    fun `a recording whose length is unknown is not dropped`() {
        assertFalse(VoipStubPolicy.endedBeforeCapture(null, farHeard = false))
    }

    @Test
    fun `the limit itself is kept`() {
        assertFalse(VoipStubPolicy.endedBeforeCapture(VoipStubPolicy.TOO_SHORT_TO_JUDGE_MS, farHeard = false))
    }
}
