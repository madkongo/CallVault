/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.phone

import com.baba.callvault.data.PrivilegedMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneRulesEvaluateTest {
    private val base = PhoneProfile.UNKNOWN

    private fun active(p: PhoneProfile) = PhoneRules.evaluate(p).map { it.id }

    @Test fun anUnknownPhoneActivatesNothing() {
        assertEquals(emptyList<String>(), active(base))
    }

    @Test fun anUnknownMakerOnEveryAndroidGetsNoIdentityRule() {
        for (sdk in listOf(34, 35, 36, 37)) {
            for (mode in listOf(PrivilegedMode.STANDALONE, PrivilegedMode.SHIZUKU, null)) {
                val ids = active(base.copy(maker = "acme", sdk = sdk, mode = mode))
                val identity = PhoneRules.entries.filter { it.kind == DetectionKind.IDENTITY }.map { it.id }.toSet()
                assertTrue("$ids", ids.none { it in identity })
            }
        }
    }

    @Test fun batteryNotExemptActivatesQ55() {
        assertTrue("Q55" in active(base.copy(batteryExempt = Tri.NO)))
        assertTrue("Q55" !in active(base.copy(batteryExempt = Tri.YES)))
    }

    @Test fun restrictedBackgroundActivatesQ56() {
        assertTrue("Q56" in active(base.copy(backgroundRestricted = Tri.YES)))
    }

    @Test fun restrictedBucketActivatesQ57ButRareOnlyLogs() {
        assertTrue("Q57" in active(base.copy(standbyBucket = StandbyBucketClass.RESTRICTED)))
        assertTrue("Q57" !in active(base.copy(standbyBucket = StandbyBucketClass.RARE)))
    }

    @Test fun hibernationOnActivatesQ54() {
        assertTrue("Q54" in active(base.copy(hibernation = Tri.YES)))
    }

    @Test fun notificationsOffActivatesQ70() {
        assertTrue("Q70" in active(base.copy(notificationsEnabled = Tri.NO)))
    }

    @Test fun shizukuWithAppCallsOffAndNoExemptionActivatesQ53() {
        val p = base.copy(mode = PrivilegedMode.SHIZUKU, appCallsOn = Tri.NO, batteryExempt = Tri.NO, sdk = 34)
        assertTrue("Q53" in active(p))
        assertTrue("Q53" !in active(p.copy(appCallsOn = Tri.YES)))
        assertTrue("Q53" !in active(p.copy(batteryExempt = Tri.YES)))
        assertTrue("Q53" !in active(p.copy(sdk = 30)))
    }

    @Test fun neverBucketDoesNotActivateQ57() {
        assertTrue("Q57" !in active(base.copy(standbyBucket = StandbyBucketClass.NEVER)))
    }

    @Test fun blockedRecordingChannelActivatesQ70EvenWithNotificationsOn() {
        assertTrue("Q70" in active(base.copy(notificationsEnabled = Tri.YES, blockedChannels = listOf("recording_channel_error"))))
        assertTrue("Q70" !in active(base.copy(notificationsEnabled = Tri.YES)))
    }

    @Test fun hibernationAndBatteryRulesAreCallLoss() {
        assertEquals(Severity.CALL_LOSS, PhoneRules.evaluate(base.copy(hibernation = Tri.YES)).single().severity)
    }

    @Test fun activeRulesAreSortedAndUnique() {
        val ids = active(base.copy(batteryExempt = Tri.NO, backgroundRestricted = Tri.YES, notificationsEnabled = Tri.NO))
        assertEquals(ids.sorted().distinct(), ids)
    }
}
