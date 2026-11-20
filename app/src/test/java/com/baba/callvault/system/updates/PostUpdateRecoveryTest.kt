/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.updates

import com.baba.callvault.data.PrivilegedMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What has to happen after the app is replaced — and the case that cost a real call.
 *
 * **The incident, 2026-09-06.** An update was installed at 11:35:05. Android stopped the app and its
 * foreground services (`FOREGROUND_SERVICE_STOP` in usage stats at exactly that second) and nothing
 * brought them back, because recovery was skipped whenever `WRITE_SECURE_SETTINGS` survived the update
 * — the reasoning being that there was nothing left to heal. But healing the grant was never the only
 * thing the app had to do: the update also **killed the privileged recorder**.
 *
 * At 11:42:59 a call came in. The app woke and started recording 14 s later, ran for eleven minutes and
 * wrote **nothing**, because there was no daemon to capture through. A 13-minute call was lost.
 */
class PostUpdateRecoveryTest {

    @Test
    fun `a replace that keeps the grant must still bring the recorder back`() {
        // THE REGRESSION. "Nothing to heal" was read as "nothing to do", and the recorder stayed dead.
        val plan = PostUpdateRecovery.plan(PrivilegedMode.STANDALONE, grantSurvived = true)
        assertTrue("the update stopped our services; something must restart them", plan.ensureRecorder)
        assertFalse("nothing to heal when the grant is still there", plan.healGrant)
        assertFalse(plan.restartShizukuService)
    }

    @Test
    fun `a replace that lost the grant heals it and still brings the recorder back`() {
        val plan = PostUpdateRecovery.plan(PrivilegedMode.STANDALONE, grantSurvived = false)
        assertTrue(plan.healGrant)
        assertTrue(plan.ensureRecorder)
    }

    @Test
    fun `Shizuku mode restarts its user service, which survives the replace holding a dead APK path`() {
        val plan = PostUpdateRecovery.plan(PrivilegedMode.SHIZUKU, grantSurvived = true)
        assertTrue(plan.restartShizukuService)
        assertTrue("and the recorder still has to come back", plan.ensureRecorder)
        assertFalse("Shizuku mode never holds WRITE_SECURE_SETTINGS to heal", plan.healGrant)
    }

    @Test
    fun `Shizuku mode does not try to heal a grant it never had, even if one is reported`() {
        val plan = PostUpdateRecovery.plan(PrivilegedMode.SHIZUKU, grantSurvived = false)
        assertFalse(plan.healGrant)
        assertTrue(plan.restartShizukuService)
    }

    @Test
    fun `the keep-alive comes back too, because it is what detects app calls`() {
        // The first version of this fix restored the daemon and not this, and the phone was left with
        // no foreground service at all — so no VoIP detection and nothing keeping the daemon warm.
        assertTrue(PostUpdateRecovery.plan(PrivilegedMode.STANDALONE, grantSurvived = true).restartKeepAlive)
        assertTrue(PostUpdateRecovery.plan(PrivilegedMode.STANDALONE, grantSurvived = false).restartKeepAlive)
        assertTrue(
            "Without app calls it is still the process anchor in Shizuku mode (R4/Q54, N26/Q53)",
            PostUpdateRecovery.plan(PrivilegedMode.SHIZUKU, grantSurvived = true, voipEnabled = false).restartKeepAlive,
        )
        assertTrue(
            "In Shizuku mode it hosts app-call detection, so it must come back when that is on",
            PostUpdateRecovery.plan(PrivilegedMode.SHIZUKU, grantSurvived = true, voipEnabled = true).restartKeepAlive,
        )
    }

    @Test
    fun `the recorder is brought back in every single case`() {
        // The one invariant worth stating on its own: whatever else is true, an app replace must never
        // leave the phone unable to record the next call.
        for (mode in PrivilegedMode.entries) {
            for (grant in listOf(true, false)) {
                assertTrue(
                    "mode=$mode grantSurvived=$grant left the recorder down",
                    PostUpdateRecovery.plan(mode, grant).ensureRecorder,
                )
            }
        }
    }
}
