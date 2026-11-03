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
    fun `an app found late goes onto the marker of a name that has none`() {
        // Telegram's call audio appears about 2.5 s after the mode flips, past the start-time
        // lookup, so its recordings were saved as plain "_voip" with no app (2026-11-01 report).
        assertEquals(
            "20261101_152259.569+0300_voip-Telegram.ogg",
            VoipLateCaller.withApp("20261101_152259.569+0300_voip.ogg", "Telegram"),
        )
    }

    @Test
    fun `an app found late sits before a caller already in the name`() {
        assertEquals(
            "20261101_152259.569+0300_voip-Telegram_Feroza.ogg",
            VoipLateCaller.withApp("20261101_152259.569+0300_voip_Feroza.ogg", "Telegram"),
        )
    }

    @Test
    fun `a name that already carries an app keeps it`() {
        val named = "20261101_152434.360+0300_voip-WhatsApp.ogg"

        assertEquals(named, VoipLateCaller.withApp(named, "Telegram"))
    }

    @Test
    fun `a blank app or a name that is not an app call changes nothing`() {
        assertEquals("x_voip.ogg", VoipLateCaller.withApp("x_voip.ogg", " "))
        assertEquals("20261101_120000_in_Feroza.ogg", VoipLateCaller.withApp("20261101_120000_in_Feroza.ogg", "Telegram"))
    }

    @Test
    fun `app and caller both found late give the full name`() {
        val name = VoipLateCaller.withCaller(VoipLateCaller.withApp("s_voip.ogg", "Telegram"), "Feroza")

        assertEquals("s_voip-Telegram_Feroza.ogg", name)
    }

    @Test
    fun `the retries are spread over the first half minute of the call`() {
        // Early enough to catch a notification posted a moment after the audio, late enough that a
        // slow app still gets a chance, and over before a short call ends.
        assertEquals(listOf(3_000L, 8_000L, 20_000L), VoipLateCaller.RETRY_DELAYS_MS)
    }

    private val off = setOf("com.whatsapp")

    @Test
    fun `an app the user records is named straight away, with no second lookup`() {
        assertEquals(VoipLateCaller.LateAppVerdict.RECORD, VoipLateCaller.verdict("org.telegram.messenger", null, off))
    }

    @Test
    fun `a switched-off app named by both lookups is discarded`() {
        assertEquals(VoipLateCaller.LateAppVerdict.DISCARD, VoipLateCaller.verdict("com.whatsapp", "com.whatsapp", off))
    }

    @Test
    fun `a switched-off app that the second lookup does not name keeps recording`() {
        assertEquals(VoipLateCaller.LateAppVerdict.UNSURE, VoipLateCaller.verdict("com.whatsapp", "org.telegram.messenger", off))
    }

    @Test
    fun `a switched-off app that the second lookup cannot find keeps recording`() {
        assertEquals(VoipLateCaller.LateAppVerdict.UNSURE, VoipLateCaller.verdict("com.whatsapp", null, off))
    }
}
