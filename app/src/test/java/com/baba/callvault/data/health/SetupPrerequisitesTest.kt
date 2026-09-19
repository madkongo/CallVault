/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.health

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.server.IRecorderService
import com.baba.callvault.server.RecorderConnection
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SetupPrerequisitesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences = AppPreferences(context)

    /**
     * Satisfies every prerequisite EXCEPT the ones a test deliberately breaks, so each test isolates
     * exactly one precedence step instead of accidentally also tripping an earlier one.
     */
    private fun satisfyAllPrerequisites() {
        preferences.setRecordingFolderUri("content://tree/a".toUri())
        preferences.setAdbPaired(true)
        Settings.Global.putString(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, "1")
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    }

    @After
    fun tearDown() {
        // RecorderConnection is a process-wide singleton; leaving it connected would leak into
        // whichever test runs next in the same JVM.
        RecorderConnection.onBinderDied()
    }

    @Test
    fun `missing recording folder is reported first, ahead of every later prerequisite`() {
        satisfyAllPrerequisites()
        preferences.setRecordingFolderUri(null)

        assertEquals(Prerequisite.RECORDING_FOLDER, SetupPrerequisites.missing(context))
    }

    @Test
    fun `missing ADB pairing is reported once the folder is set`() {
        satisfyAllPrerequisites()
        preferences.setAdbPaired(false)

        assertEquals(Prerequisite.ADB_PAIRING, SetupPrerequisites.missing(context))
    }

    @Test
    fun `a bare zero from the developer-options setting no longer blocks setup`() {
        // ⚠️ Used to assert DEVELOPER_OPTIONS here. Android 17 returns `0` to every app whatever the
        // truth, so that assertion made every Android 17 phone report a broken setup and log "this call
        // was not recorded" against calls that recorded perfectly. A zero now has to be corroborated --
        // see DeveloperOptionsPolicy -- and Robolectric cannot supply the corroborator, so nothing is
        // claimed. The decision itself is covered exhaustively in SetupPrerequisitesModeTest.
        satisfyAllPrerequisites()
        Settings.Global.putString(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, "0")

        assertEquals(null, SetupPrerequisites.missing(context))
    }

    @Test
    fun `missing secure settings grant is reported only when the daemon is also disconnected`() {
        satisfyAllPrerequisites()
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .denyPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS)

        assertEquals(Prerequisite.SECURE_SETTINGS_GRANT, SetupPrerequisites.missing(context))
    }

    @Test
    fun `a missing secure settings grant is excused while the daemon is connected`() {
        // The grant is only needed to relaunch a DEAD daemon; a live one means recording works right
        // now regardless of the grant, so this must NOT read as missing.
        satisfyAllPrerequisites()
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .denyPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        RecorderConnection.onBinderReceived(mockk<IRecorderService>(relaxed = true))

        assertNull(SetupPrerequisites.missing(context))
    }

    @Test
    fun `nothing is missing once every prerequisite is met`() {
        satisfyAllPrerequisites()

        assertNull(SetupPrerequisites.missing(context))
    }

    @Test
    fun `an absent developer-options global is not treated as missing`() {
        // Mirrors DeveloperOptions.isExplicitlyDisabled: a ROM that doesn't expose the global must
        // not paint a permanent red banner.
        preferences.setRecordingFolderUri("content://tree/a".toUri())
        preferences.setAdbPaired(true)
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS)

        assertNull(SetupPrerequisites.missing(context))
    }

    // ── recordMissedForMissingPrerequisite: the CallSessionManager seam ───────────────────────
    //
    // CallSessionManager.evaluateAndStartService() is not practical to drive directly under
    // Robolectric: it is a process-wide singleton wired to live TelephonyManager broadcasts, a
    // 500ms coroutine "verification window", and contact-lookup content-resolver queries, none of
    // which this feature touches. The actual decision it needs — "if a prerequisite is missing AND
    // the setup has verified before, report a MISSED-WHILE-NOT-READY entry (never the generic gap);
    // otherwise report nothing" — is entirely captured by [recordMissedForMissingPrerequisite],
    // which CallSessionManager calls verbatim. Testing here exercises the exact function production
    // code runs, rather than re-deriving the same branch inside the test.

    @Test
    fun `a prerequisite-missing call start on a previously-verified setup records a MISSED-WHILE-NOT-READY entry, not a generic gap`() {
        val store = SetupHealthStore(context)
        store.recordVerified(500L, "fp-1")

        store.recordMissedForMissingPrerequisite(Prerequisite.DEVELOPER_OPTIONS, 1_000L, "Alice")

        val facts = store.read()
        assertEquals(1_000L, facts.lastNotReadyAt)
        assertEquals("Alice", facts.lastNotReadyLabel)
        assertEquals(Prerequisite.DEVELOPER_OPTIONS, facts.lastNotReadyPrerequisite)
        // Must never blur into the generic (daemon-died) gap this feature also tracks.
        assertEquals(0L, facts.lastGapAt)
        assertNull(facts.lastGapLabel)
    }

    @Test
    fun `a prerequisites-met call start records nothing`() {
        val store = SetupHealthStore(context)
        store.recordVerified(500L, "fp-1")

        store.recordMissedForMissingPrerequisite(null, 1_000L, "Alice")

        val facts = store.read()
        assertEquals(0L, facts.lastNotReadyAt)
        assertNull(facts.lastNotReadyLabel)
        assertNull(facts.lastNotReadyPrerequisite)
    }

    @Test
    fun `a setup that has never verified a working call records nothing, even with a prerequisite missing`() {
        // The gate: mid-onboarding (no folder yet, never paired) must never be told a call was
        // "missed" — there is no earlier proof recording ever worked to have lost.
        val store = SetupHealthStore(context)

        store.recordMissedForMissingPrerequisite(Prerequisite.RECORDING_FOLDER, 1_000L, "Alice")

        val facts = store.read()
        assertEquals(0L, facts.lastNotReadyAt)
        assertNull(facts.lastNotReadyLabel)
        assertNull(facts.lastNotReadyPrerequisite)
    }
}
