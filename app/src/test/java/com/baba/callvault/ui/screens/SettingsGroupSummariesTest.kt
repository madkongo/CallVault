/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/** The one-line summaries on the new Settings screen's group cards. */
class SettingsGroupSummariesTest {

    @Test
    fun `phone calls off reads off whatever the direction switches say`() {
        assertEquals(RecordingSummary.OFF, recordingSummary(isCarrierOn = false, isIncomingOn = true, isOutgoingOn = true))
    }

    @Test
    fun `recording summary names the directions that record automatically`() {
        assertEquals(RecordingSummary.BOTH, recordingSummary(true, isIncomingOn = true, isOutgoingOn = true))
        assertEquals(RecordingSummary.INCOMING_ONLY, recordingSummary(true, isIncomingOn = true, isOutgoingOn = false))
        assertEquals(RecordingSummary.OUTGOING_ONLY, recordingSummary(true, isIncomingOn = false, isOutgoingOn = true))
        assertEquals(RecordingSummary.MANUAL_ONLY, recordingSummary(true, isIncomingOn = false, isOutgoingOn = false))
    }

    @Test
    fun `count summary counts only switches the user can use`() {
        val switches = listOf(
            SwitchSummaryInput(isOn = true, isAvailable = true),
            SwitchSummaryInput(isOn = true, isAvailable = false), // stored on, drawn off and greyed
            SwitchSummaryInput(isOn = false, isAvailable = true),
            SwitchSummaryInput(isOn = true, isAvailable = true),
            SwitchSummaryInput(isOn = false, isAvailable = true),
        )
        assertEquals(CountSummary(on = 2, total = 4), countSummary(switches))
    }

    @Test
    fun `count summary of nothing usable has a zero total`() {
        assertEquals(CountSummary(0, 0), countSummary(listOf(SwitchSummaryInput(true, false))))
        assertEquals(CountSummary(0, 0), countSummary(emptyList()))
    }

    @Test
    fun `join skips blank parts and uses one separator`() {
        assertEquals("On · incoming only", joinSummary(listOf("On", null, "", "incoming only")))
        assertEquals("Opus", joinSummary(listOf("Opus")))
        assertEquals("", joinSummary(listOf(null, " ")))
    }

    @Test
    fun `calls to record names phone and app calls and the phone directions`() {
        assertEquals(
            CallsToRecordSummary(CallsWhich.PHONE_AND_APP, RecordingSummary.BOTH),
            callsToRecordSummary(isPhoneOn = true, isAppOn = true, isIncomingOn = true, isOutgoingOn = true),
        )
        assertEquals(
            CallsToRecordSummary(CallsWhich.PHONE_ONLY, RecordingSummary.INCOMING_ONLY),
            callsToRecordSummary(isPhoneOn = true, isAppOn = false, isIncomingOn = true, isOutgoingOn = false),
        )
        assertEquals(
            CallsToRecordSummary(CallsWhich.PHONE_ONLY, RecordingSummary.MANUAL_ONLY),
            callsToRecordSummary(isPhoneOn = true, isAppOn = false, isIncomingOn = false, isOutgoingOn = false),
        )
    }

    @Test
    fun `app calls alone carry no direction, and nothing on reads off`() {
        assertEquals(
            CallsToRecordSummary(CallsWhich.APP_ONLY, null),
            callsToRecordSummary(isPhoneOn = false, isAppOn = true, isIncomingOn = true, isOutgoingOn = true),
        )
        assertEquals(
            CallsToRecordSummary(CallsWhich.NONE, null),
            callsToRecordSummary(isPhoneOn = false, isAppOn = false, isIncomingOn = true, isOutgoingOn = true),
        )
    }

    @Test
    fun `skip rules count only the rules that are on`() {
        assertEquals(0, activeRuleCount(listOf(false, false, false)))
        assertEquals(2, activeRuleCount(listOf(true, false, true)))
        assertEquals(0, activeRuleCount(emptyList()))
    }

    @Test
    fun `stay paired switch counts in standalone mode`() {
        assertEquals(SwitchSummaryInput(isOn = true, isAvailable = true), keepPairedSummaryInput(isOn = true, usesEmbeddedAdb = true, isSetByCallVault = false))
        assertEquals(CountSummary(1, 1), countSummary(listOf(keepPairedSummaryInput(true, true, false))))
    }

    @Test
    fun `stay paired switch counts in Shizuku mode only when CallVault set it`() {
        assertEquals(CountSummary(1, 1), countSummary(listOf(keepPairedSummaryInput(true, false, true))))
        assertEquals(CountSummary(0, 0), countSummary(listOf(keepPairedSummaryInput(true, false, false))))
    }
}
