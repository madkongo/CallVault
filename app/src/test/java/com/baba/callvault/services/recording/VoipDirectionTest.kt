/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.data.recordings.RecordingDirection
import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.server.VoipCallFacts
import com.baba.callvault.server.VoipCallerName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * BabApps item 113: the in/out badge of an app call. The rule is that a direction is said only on a
 * positive structural signal; every unclear shape stays null. Shapes follow real
 * `cmd notification get` records (WhatsApp measured on the OP9, 2026-09-22; Telegram from its source).
 */
class VoipDirectionTest {

    /** A record shaped like `Notification.toString()` plus extras, as the dump prints it. */
    private fun record(
        pkg: String,
        ongoing: Boolean = true,
        category: String? = null,
        actions: Int = 0,
        callType: Int? = null,
        chronometer: Boolean? = null,
        extra: String = "",
        title: String = "Someone",
    ) = """
        NotificationRecord(0x01: pkg=$pkg user=UserHandle{0} id=201 tag=null
          Notification(channel=calls pri=2 flags=${if (ongoing) "0x62" else "0x10"}${category?.let { " category=$it" } ?: ""} actions=$actions vis=PRIVATE)
          extras={
                android.title=String ($title)
                android.text=String (text)
                ${callType?.let { "android.callType=Integer ($it)" } ?: ""}
                ${chronometer?.let { "android.showChronometer=Boolean ($it)" } ?: ""}
                $extra
          }
    """.trimIndent()

    private fun direction(dump: String, pkg: String) =
        VoipDirection.decide(VoipCallerName.extractFacts(dump, pkg))

    // --- OUTGOING: ringing at the far end -------------------------------------------------------

    @Test
    fun `WhatsApp ringing on a call we placed is outgoing`() {
        val dump = record("com.whatsapp", category = "call", actions = 1, callType = 2, chronometer = false)
        assertEquals(RecordingDirection.OUTGOING, direction(dump, "com.whatsapp"))
    }

    @Test
    fun `an ongoing call notification with a stopped timer and no category is outgoing`() {
        val dump = record("org.thoughtcrime.securesms", actions = 1, chronometer = false)
        assertEquals(RecordingDirection.OUTGOING, direction(dump, "org.thoughtcrime.securesms"))
    }

    // --- INCOMING: only on a clear incoming marker ----------------------------------------------

    @Test
    fun `a CallStyle of type incoming is incoming even when ongoing and the timer is stopped`() {
        val dump = record("org.thoughtcrime.securesms", category = "call", actions = 2, callType = 1, chronometer = false)
        assertEquals(RecordingDirection.INCOMING, direction(dump, "org.thoughtcrime.securesms"))
    }

    @Test
    fun `an app's own incoming flag is incoming`() {
        val dump = record("jp.naver.line.android", category = "call", actions = 2, extra = "IS_INCOMING_CALL=Boolean (true)")
        assertEquals(RecordingDirection.INCOMING, direction(dump, "jp.naver.line.android"))
    }

    @Test
    fun `a call heads-up with answer and decline that is not ongoing is incoming`() {
        val dump = record("com.facebook.orca", ongoing = false, category = "call", actions = 2)
        assertEquals(RecordingDirection.INCOMING, direction(dump, "com.facebook.orca"))
    }

    // --- null: anything unclear ------------------------------------------------------------------

    @Test
    fun `an answered call is unknown`() {
        val dump = record("com.whatsapp", category = "call", actions = 3, callType = 2, chronometer = true)
        assertNull(direction(dump, "com.whatsapp"))
    }

    @Test
    fun `an ongoing call with two actions and no timer is hang up and mute, not answer and decline`() {
        val dump = record("com.viber.voip", category = "call", actions = 2)
        assertNull(direction(dump, "com.viber.voip"))
    }

    @Test
    fun `a Telegram incoming call stays unknown whatever its title says`() {
        // Telegram titles every 1:1 call "Outgoing call" (VoIPService.showNotification), sets no
        // category, no CallStyle and no timer flag, with one Hang up action and ongoing set.
        val dump = record("org.telegram.messenger", actions = 1, title = "Outgoing call")
        assertNull(VoipCallerName.extractFacts(dump, "org.telegram.messenger"))
        assertNull(direction(dump, "org.telegram.messenger"))
    }

    @Test
    fun `a Telegram fork outgoing call is unknown too`() {
        val dump = record("com.radolyn.ayugram", actions = 1, title = "Outgoing call")
        assertNull(direction(dump, "com.radolyn.ayugram"))
    }

    @Test
    fun `a chat message of the same app is not a call`() {
        val dump = record("com.whatsapp", ongoing = false, category = "msg", actions = 2)
        assertNull(direction(dump, "com.whatsapp"))
    }

    @Test
    fun `another app's notification is not read`() {
        val dump = record("com.whatsapp", category = "call", actions = 1, callType = 2, chronometer = false)
        assertNull(direction(dump, "org.telegram.messenger"))
    }

    @Test
    fun `no facts is unknown`() {
        assertNull(VoipDirection.decide(null))
        assertNull(VoipCallerName.extractFacts("", "com.whatsapp"))
    }

    // --- set once ---------------------------------------------------------------------------------

    @Test
    fun `the first result is kept and never replaced by a later one`() {
        assertEquals(RecordingDirection.OUTGOING, VoipDirection.keepFirst(RecordingDirection.OUTGOING, RecordingDirection.INCOMING))
        assertEquals(RecordingDirection.INCOMING, VoipDirection.keepFirst(null, RecordingDirection.INCOMING))
        assertNull(VoipDirection.keepFirst(null, null))
    }

    // --- facts over the binder ---------------------------------------------------------------------

    @Test
    fun `facts survive the binder line and carry no text`() {
        val facts = VoipCallFacts("call", 2, 1, false, true, null)
        val line = facts.encode()
        assertEquals(facts, VoipCallFacts.parse(line))
        val unset = VoipCallFacts(null, 0, null, null, false, null)
        assertEquals(unset, VoipCallFacts.parse(unset.encode()))
        assertNull(VoipCallFacts.parse(""))
        assertNull(VoipCallFacts.parse(null))
    }

    // --- the name ------------------------------------------------------------------------------------

    private val stamp = "20261117_120000.000+0200"

    @Test
    fun `the direction goes in front of the voip marker and reads back as the badge`() {
        val out = VoipLateCaller.withDirection("${stamp}_voip-WhatsApp_Dana.ogg", "out")
        assertEquals("${stamp}_out_voip-WhatsApp_Dana.ogg", out)
        val parsed = RecordingsRepository.parseName(out)
        assertEquals(RecordingDirection.OUTGOING, parsed.direction)
        assertEquals("WhatsApp", parsed.voipApp)
        assertEquals("Dana", parsed.contactName)
        assertEquals(RecordingsRepository.parseName("${stamp}_voip-WhatsApp_Dana.ogg").startedAtMillis, parsed.startedAtMillis)
        assertEquals(RecordingsRepository.parseName("${stamp}_voip-WhatsApp_Dana.ogg").displayDate, parsed.displayDate)
    }

    @Test
    fun `an unknown direction leaves the name and the parse exactly as before`() {
        val name = "${stamp}_voip-Telegram_Dana.ogg"
        assertEquals(name, VoipLateCaller.withDirection(name, null))
        assertNull(RecordingsRepository.parseName(name).direction)
        assertNull(RecordingsRepository.parseName("${stamp}_voip.ogg").direction)
    }

    @Test
    fun `a caller called in or out is a caller, not a direction`() {
        val parsed = RecordingsRepository.parseName("${stamp}_voip-WhatsApp_in.ogg")
        assertNull(parsed.direction)
        assertEquals("in", parsed.contactName)
        assertEquals(RecordingDirection.INCOMING, RecordingsRepository.parseName("${stamp}_in_voip-WhatsApp_out.ogg").direction)
    }

    @Test
    fun `the direction is added once and a name that is not an app call is untouched`() {
        val once = VoipLateCaller.withDirection("${stamp}_out_voip-WhatsApp.ogg", "in")
        assertEquals("${stamp}_out_voip-WhatsApp.ogg", once)
        val carrier = "${stamp}_in_+15551234567.ogg"
        assertEquals(carrier, VoipLateCaller.withDirection(carrier, "out"))
    }

    @Test
    fun `a name with a direction is still an app call, never a carrier call`() {
        val name = "${stamp}_out_voip-WhatsApp_Dana.ogg"
        assertEquals(false, RecordingsRepository.isCarrierCall(name))
        assertEquals(false, RecordingsRepository.isCarrierCallWithoutDirection(name))
    }
}
