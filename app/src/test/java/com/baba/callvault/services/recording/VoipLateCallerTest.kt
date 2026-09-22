/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A caller learned after the recording started still reaches the file name.
 *
 * The name is first asked for at the instant the audio mode flips, which can be before the VoIP app
 * has posted its call notification. The file is only published when the call ends, so a name found
 * a few seconds in simply goes into the name it is published under.
 */
class VoipLateCallerTest {

    @Test
    fun `a caller is added before the extension of a name that has none`() {
        assertEquals(
            "20260919_204709.821+0300_voip-WhatsApp_Feroza.ogg",
            VoipLateCaller.withCaller("20260919_204709.821+0300_voip-WhatsApp.ogg", "Feroza"),
        )
    }

    @Test
    fun `a name that already carries a caller is left alone`() {
        val named = "20260919_204709.821+0300_voip-WhatsApp_Feroza.ogg"

        assertEquals(named, VoipLateCaller.withCaller(named, "Someone Else"))
    }

    @Test
    fun `a call with no app still gets its caller`() {
        assertEquals("20260919_204709.821+0300_voip_Feroza.ogg", VoipLateCaller.withCaller("20260919_204709.821+0300_voip.ogg", "Feroza"))
    }

    @Test
    fun `a blank caller changes nothing`() {
        assertEquals("x_voip.ogg", VoipLateCaller.withCaller("x_voip.ogg", "  "))
    }

    @Test
    fun `the retries are spread over the first half minute of the call`() {
        // Early enough to catch a notification posted a moment after the audio, late enough that a
        // slow app still gets a chance, and over before a short call ends.
        assertEquals(listOf(3_000L, 8_000L, 20_000L), VoipLateCaller.RETRY_DELAYS_MS)
    }
}
