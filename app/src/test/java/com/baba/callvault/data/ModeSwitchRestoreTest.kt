/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A mode round trip must be **lossless**: what CallVault turned off because the mode could not do it
 * gets turned back on when a mode can do it again, and nothing else does.
 *
 * Before this existed, the switch was a one-way door. Trying Shizuku once and coming straight back left
 * resilient recording, VoIP recording and offline recording off for good, and nothing on screen ever
 * said so — the list went only to the log. Recording still worked, so there was no symptom; the user
 * simply lost the setup they had chosen.
 *
 * The safety property that made the door one-way in the first place still holds, and these tests are
 * mostly here to keep it holding: **only switches CallVault itself turned off are ever turned back on.**
 * A switch the user turned off stays off through any number of mode changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModeSwitchRestoreTest {

    private val prefs = AppPreferences(ApplicationProvider.getApplicationContext())

    private fun enterShizuku() = prefs.disableWhatModeCannotDo(PrivilegedMode.SHIZUKU)
    private fun returnToStandalone(): Set<ModeCapability> {
        val restored = prefs.restoreWhatModeCanDoAgain(PrivilegedMode.STANDALONE)
        prefs.disableWhatModeCannotDo(PrivilegedMode.STANDALONE)
        return restored
    }

    @Test
    fun `a switch CallVault turned off comes back on returning to standalone`() {
        prefs.setHandoffPersistEnabled(true)

        enterShizuku()
        assertFalse("Shizuku cannot do handoff, so it must be off there", prefs.isHandoffPersistEnabled())

        returnToStandalone()
        assertTrue("Standalone can do handoff again, so it must come back", prefs.isHandoffPersistEnabled())
    }

    @Test
    fun `a switch the user turned off stays off`() {
        prefs.setHandoffPersistEnabled(false)

        enterShizuku()
        returnToStandalone()

        assertFalse(
            "Restoring must never enable something the user had deliberately off",
            prefs.isHandoffPersistEnabled()
        )
    }

    @Test
    fun `VoIP auto-start is remembered separately from VoIP recording`() {
        // The two share one capability but are two switches; restoring the capability wholesale would
        // silently turn auto-start on for someone who only ever wanted the manual prompt.
        prefs.setVoipRecordingEnabled(true)
        prefs.setVoipAutoStartEnabled(false)

        enterShizuku()
        returnToStandalone()

        assertTrue("VoIP recording was on before, so it comes back", prefs.isVoipRecordingEnabled())
        assertFalse("Auto-start was off before, so it stays off", prefs.isVoipAutoStartEnabled())
    }

    @Test
    fun `every capability the mode took away is restored`() {
        prefs.setHandoffPersistEnabled(true)
        prefs.setVoipRecordingEnabled(true)
        prefs.setOfflineRecordingEnabled(true)

        val turnedOff = enterShizuku()
        val restored = returnToStandalone()

        assertEquals("What came back must be exactly what went away", turnedOff, restored)
        assertTrue(prefs.isHandoffPersistEnabled())
        assertTrue(prefs.isVoipRecordingEnabled())
        assertTrue(prefs.isOfflineRecordingEnabled())
    }

    @Test
    fun `restoring twice does not resurrect a switch turned off in between`() {
        prefs.setHandoffPersistEnabled(true)
        enterShizuku()
        returnToStandalone()

        // The user changes their mind while back in standalone.
        prefs.setHandoffPersistEnabled(false)

        // Anything that re-runs the reconcile — an app start, say — must leave that alone.
        assertTrue(
            "The record should have been consumed by the first restore",
            prefs.restoreWhatModeCanDoAgain(PrivilegedMode.STANDALONE).isEmpty()
        )
        assertFalse(prefs.isHandoffPersistEnabled())
    }

    @Test
    fun `a second round trip remembers the newer answer`() {
        prefs.setHandoffPersistEnabled(true)
        enterShizuku()
        returnToStandalone()

        prefs.setHandoffPersistEnabled(false)
        enterShizuku()
        returnToStandalone()

        assertFalse("The second trip started from off, so it must end off", prefs.isHandoffPersistEnabled())
    }

    @Test
    fun `restoring in a mode that still cannot do it keeps the record for later`() {
        prefs.setHandoffPersistEnabled(true)
        enterShizuku()

        // An app start while still in Shizuku mode must not restore, and must not forget either.
        assertTrue(prefs.restoreWhatModeCanDoAgain(PrivilegedMode.SHIZUKU).isEmpty())
        assertFalse(prefs.isHandoffPersistEnabled())

        returnToStandalone()
        assertTrue("The record survived the no-op restore", prefs.isHandoffPersistEnabled())
    }

    @Test
    fun `nothing to restore is not an error`() {
        assertTrue(prefs.restoreWhatModeCanDoAgain(PrivilegedMode.STANDALONE).isEmpty())
    }

    // ---- Android version (backlog #1)

    @Test
    fun `an existing user's app-call switch is turned off on Android 13, and reported`() {
        prefs.setVoipRecordingEnabled(true)
        prefs.setHandoffPersistEnabled(true)
        val turnedOff = prefs.disableWhatModeCannotDo(PrivilegedMode.STANDALONE, sdk = 33)
        assertFalse(prefs.isVoipRecordingEnabled())
        assertTrue("Resilient works on 13, so it stays on", prefs.isHandoffPersistEnabled())
        assertEquals(setOf(ModeCapability.VOIP_RECORDING), turnedOff)
    }

    @Test
    fun `nothing is turned off on Android 14 and newer`() {
        prefs.setVoipRecordingEnabled(true)
        prefs.setHandoffPersistEnabled(true)
        assertTrue(prefs.disableWhatModeCannotDo(PrivilegedMode.STANDALONE, sdk = 34).isEmpty())
        assertTrue(prefs.isVoipRecordingEnabled())
    }

    @Test
    fun `a version-blocked switch is never turned back on by a restore`() {
        prefs.setVoipRecordingEnabled(true)
        prefs.disableWhatModeCannotDo(PrivilegedMode.STANDALONE, sdk = 33)
        prefs.restoreWhatModeCanDoAgain(PrivilegedMode.STANDALONE, sdk = 33)
        assertFalse(prefs.isVoipRecordingEnabled())
    }
}
