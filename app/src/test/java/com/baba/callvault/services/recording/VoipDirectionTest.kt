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

    private fun direction(
        dump: String,
        pkg: String,
        ring: VoipRingMemory.Verdict = VoipRingMemory.Verdict.NONE,
        listening: Boolean = true,
    ) = VoipDirection.decide(VoipCallerName.extractFacts(dump, pkg), ring, listening)

    // --- the real shapes the OP9 posted on 2026-11-17 (flags and channels as dumped) -------------------

    @Test
    fun `the WhatsApp ringing notification of an incoming call is incoming, though ongoing and timer stopped`() {
        // channel voip_notification_5, flags 0x22e2 (ongoing), category=call, 2 actions, callType 1.
        val dump = record("com.whatsapp", category = "call", actions = 2, callType = 1, chronometer = false)
        assertEquals(RecordingDirection.INCOMING, direction(dump, "com.whatsapp", listening = false))
    }

    @Test
    fun `the Telegram ringing notification of an incoming call is incoming`() {
        // id 203, channel incoming_calls40, flags 0x80 (not ongoing), category=call, Decline + Answer, callType 1.
        val dump = record("org.telegram.messenger", ongoing = false, category = "call", actions = 2, callType = 1)
        assertEquals(RecordingDirection.INCOMING, direction(dump, "org.telegram.messenger"))
    }

    @Test
    fun `a slow pickup cannot turn an incoming call outgoing`() {
        // The notification already rewritten as ongoing with the timer not yet started: the shape of an
        // outgoing call. The ring listener heard the incoming notification, so it is incoming; and if the
        // ring was heard but not tied to this call, it is unknown, never outgoing.
        val shape = record("com.whatsapp", category = "call", actions = 1, callType = 2, chronometer = false)
        assertEquals(RecordingDirection.INCOMING, direction(shape, "com.whatsapp", VoipRingMemory.Verdict.ANSWERED_RING))
        assertNull(direction(shape, "com.whatsapp", VoipRingMemory.Verdict.UNRESOLVED_RING))
    }

    @Test
    fun `outgoing is not claimed while the ring listener is not connected`() {
        val shape = record("com.whatsapp", category = "call", actions = 1, callType = 2, chronometer = false)
        assertNull(direction(shape, "com.whatsapp", listening = false))
    }

    @Test
    fun `a Telegram call is outgoing only when the listener is connected and no ring was heard`() {
        val none = VoipRingMemory.Verdict.NONE
        // The ongoing notification says nothing either way; the absence of the incoming notice does.
        val ongoing = record("org.telegram.messenger", actions = 1, title = "Ongoing Telegram call")
        val facts = VoipCallerName.extractFacts(ongoing, "org.telegram.messenger")
        assertEquals(RecordingDirection.OUTGOING, VoipDirection.decide(facts, none, true, true))
        assertEquals(RecordingDirection.OUTGOING, VoipDirection.decide(null, none, true, true))
        // No listener: nothing is known. A ring heard but not answered: nothing is known.
        assertNull(VoipDirection.decide(null, none, false, true))
        assertNull(VoipDirection.decide(null, VoipRingMemory.Verdict.UNRESOLVED_RING, true, true))
        // The answered ring beats everything.
        assertEquals(RecordingDirection.INCOMING, VoipDirection.decide(null, VoipRingMemory.Verdict.ANSWERED_RING, true, true))
        // An app not measured to announce every incoming call never gets outgoing from absence alone.
        assertNull(VoipDirection.decide(null, none, true, false))
    }

    // --- review C20: "no ring heard" means outgoing only for a fresh, first, watched start -----------------

    private val tgPkg = "org.telegram.messenger"
    private val minute = 60_000L

    @Test
    fun `absence means outgoing when the listener watched since before the call could ring`() {
        assertEquals(true, VoipDirection.absenceMeansOutgoing(true, listenerConnectedSince = 1_000, callDetectedAt = 1_000 + 2 * minute, lastRecordingEndedAt = 0))
    }

    @Test
    fun `a listener that connected after the call could have rung proves nothing`() {
        // A CallVault restarted into a call already up (Q25), or a listener reconnected late.
        assertEquals(false, VoipDirection.absenceMeansOutgoing(true, listenerConnectedSince = 1_000, callDetectedAt = 1_000 + 30_000, lastRecordingEndedAt = 0))
        assertEquals(false, VoipDirection.absenceMeansOutgoing(true, listenerConnectedSince = 0, callDetectedAt = 10 * minute, lastRecordingEndedAt = 0))
    }

    @Test
    fun `a second recording of the same call after a split is never outgoing from absence`() {
        // The first recording consumed the answer; the call split (a mode drop, or a phone call taken in it).
        assertEquals(false, VoipDirection.absenceMeansOutgoing(true, listenerConnectedSince = 1_000, callDetectedAt = 10 * minute, lastRecordingEndedAt = 10 * minute - 5_000))
    }

    @Test
    fun `an app not measured to announce every incoming call never gets outgoing from absence`() {
        assertEquals(false, VoipDirection.absenceMeansOutgoing(false, listenerConnectedSince = 1_000, callDetectedAt = 10 * minute, lastRecordingEndedAt = 0))
    }

    @Test
    fun `an Ask me start two minutes into an answered Telegram call reads the ring as it was at the call's start`() {
        // Review C20 (a): the incoming call rang and was answered; the user tapped Record 120 s later. Read at the
        // tap the memory had forgotten the answer and the call was labelled outgoing.
        VoipRingMemory.clearForTest()
        try {
            VoipRingMemory.onPosted(tgPkg, "0|tg|203", incomingMarker = true, ongoing = false, groupSummary = false, now = 10_000)
            VoipRingMemory.onRemoved(tgPkg, "0|tg|203", 20_000)
            VoipRingMemory.onPosted(tgPkg, "0|tg|201", incomingMarker = false, ongoing = true, groupSummary = false, now = 20_400)
            val detectedAt = 20_380L
            assertEquals(VoipRingMemory.Verdict.NONE, VoipRingMemory.verdict(tgPkg, detectedAt + 2 * minute))
            assertEquals(VoipRingMemory.Verdict.ANSWERED_RING, VoipRingMemory.verdict(tgPkg, VoipDirection.ringEvidenceAt(detectedAt)))
        } finally {
            VoipRingMemory.clearForTest()
        }
    }

    // --- review C30: a missed-call notice is not a ring ------------------------------------------------

    @Test
    fun `to the ring listener a call notice with two actions is a ring only with a full-screen intent`() {
        // A missed-call notice (Call back, Message) has the shape category=call, two actions, not ongoing, and no
        // full-screen intent; it left the ring "active" and the next outgoing call read as incoming.
        val missed = VoipCallFacts("call", 2, null, null, false, null)
        assertEquals(false, VoipDirection.isRingMarker(missed, hasFullScreenIntent = false))
        assertEquals(true, VoipDirection.isRingMarker(missed, hasFullScreenIntent = true))
        // A CallStyle of type incoming (Telegram, WhatsApp) or the incoming flag needs nothing more.
        assertEquals(true, VoipDirection.isRingMarker(VoipCallFacts("call", 2, 1, null, false, null), hasFullScreenIntent = false))
        assertEquals(true, VoipDirection.isRingMarker(VoipCallFacts(null, 0, null, null, false, true), hasFullScreenIntent = false))
    }

    @Test
    fun `an old missed-call notice beside the ongoing call does not decide the call's direction`() {
        val missed = record("com.whatsapp", ongoing = false, category = "call", actions = 2)
        val calling = record("com.whatsapp", category = "call", actions = 1, callType = 2, chronometer = false)
        assertEquals(RecordingDirection.OUTGOING, direction(missed + "\n" + calling, "com.whatsapp"))
    }

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

    // C128: the notification is read for the direction once; nothing is asked of the recorder after the first read.
    @Test
    fun `no more reads once the first read has decided the direction`() {
        assertEquals(false, VoipDirection.readAgain(hasFirstRead = true, elapsedMs = 600L))
    }

    @Test
    fun `reads continue until the first notification is found, within the grace`() {
        assertEquals(true, VoipDirection.readAgain(hasFirstRead = false, elapsedMs = 600L))
    }

    @Test
    fun `reads stop when no call notification appears within the grace`() {
        assertEquals(false, VoipDirection.readAgain(hasFirstRead = false, elapsedMs = VoipAnswerHold.NOTIFICATION_GRACE_MS))
    }

    @Test
    fun `a name with a direction is still an app call, never a carrier call`() {
        val name = "${stamp}_out_voip-WhatsApp_Dana.ogg"
        assertEquals(false, RecordingsRepository.isCarrierCall(name))
        assertEquals(false, RecordingsRepository.isCarrierCallWithoutDirection(name))
    }
}
