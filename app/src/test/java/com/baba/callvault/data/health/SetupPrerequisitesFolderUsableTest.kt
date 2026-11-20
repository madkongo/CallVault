/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.health

import com.baba.callvault.data.PrivilegedMode
import com.baba.callvault.server.ShizukuStatus
import com.baba.callvault.system.storage.FolderCheckState
import com.baba.callvault.system.storage.FolderReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Review C98: "configured" is not "usable". Home said Ready for a folder the user had deleted. Readiness now takes the
 * stored measurement of the folder (with its time), so missing() stays free of I/O.
 */
class SetupPrerequisitesFolderUsableTest {

    private val uri = "content://com.android.externalstorage.documents/tree/primary%3ARecordings"
    private val now = 10_000_000L

    private fun gone(at: Long = now, forUri: String = uri) = FolderCheckState(
        uri = forUri, kind = FolderReading.Kind.GRANT_MISSING, measuredAt = at, firstBadAt = at,
        confirmedUnusable = true, alerted = true,
    )

    private fun firstMissing(
        mode: PrivilegedMode = PrivilegedMode.STANDALONE,
        measurement: FolderCheckState?,
        hasFolder: Boolean = true,
        daemonConnected: Boolean = false,
        isPaired: Boolean = true,
    ) = SetupPrerequisites.firstMissing(
        mode = mode,
        hasFolder = hasFolder,
        isPaired = isPaired,
        devOptionsDisabled = false,
        hasSecureSettings = true,
        daemonConnected = daemonConnected,
        shizuku = ShizukuStatus.READY,
        folderUri = if (hasFolder) uri else null,
        folderMeasurement = measurement,
        now = now,
    )

    @Test
    fun `a folder measured as gone is not ready, ahead of everything after the folder`() {
        assertEquals(Prerequisite.FOLDER_UNAVAILABLE, firstMissing(measurement = gone(), isPaired = false))
    }

    @Test
    fun `a gone folder outranks a connected recorder in both modes`() {
        assertEquals(Prerequisite.FOLDER_UNAVAILABLE, firstMissing(measurement = gone(), daemonConnected = true))
        assertEquals(
            Prerequisite.FOLDER_UNAVAILABLE,
            firstMissing(mode = PrivilegedMode.SHIZUKU, measurement = gone(), daemonConnected = true),
        )
    }

    @Test
    fun `no folder at all is still the onboarding's case`() {
        assertEquals(Prerequisite.RECORDING_FOLDER, firstMissing(measurement = gone(), hasFolder = false))
    }

    @Test
    fun `phones whose folder works are unchanged - no measurement, a usable one, a stale or another folder's`() {
        assertNull(firstMissing(measurement = null))
        assertNull(firstMissing(measurement = gone().copy(confirmedUnusable = false, kind = FolderReading.Kind.OK)))
        assertNull(firstMissing(measurement = gone(at = now - 25 * 60 * 60_000L)))
        assertNull(firstMissing(measurement = gone(forUri = "content://x/tree/other")))
    }

    @Test
    fun `a call while the folder is gone is not reported missed - it is kept and saved later`() {
        // CallSessionManager reports through this; FOLDER_UNAVAILABLE calls are recorded into waiting storage.
        val store = FakeMissedSink()
        store.report(Prerequisite.FOLDER_UNAVAILABLE)
        assertEquals(0, store.recorded)
        store.report(Prerequisite.RECORDING_FOLDER)
        assertEquals(1, store.recorded)
    }

    /** The gate logic of recordMissedForMissingPrerequisite, run without a store. */
    private class FakeMissedSink {
        var recorded = 0
        fun report(missing: Prerequisite?) {
            if (SetupPrerequisites.leavesCallUnrecorded(missing)) recorded++
        }
    }
}
