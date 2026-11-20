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
 * A part the keep-alive went on without must still show in every debug report, with logging off too (owner rule: every
 * failure visible). Once per process: the keep-alive restarts often, and one line says it all.
 */
class UnavailablePartLogTest {

    private val failure = NoClassDefFoundError("Failed resolution of: Landroid/media/AudioManager\$OnModeChangedListener;")

    @Test
    fun `the line names the part and the error`() {
        assertEquals(
            "app-call detection unavailable: NoClassDefFoundError: Failed resolution of: Landroid/media/AudioManager\$OnModeChangedListener;",
            UnavailablePartLog.line("App-call detection", failure),
        )
    }

    @Test
    fun `a part is written once per process however often it fails`() {
        val written = mutableListOf<String>()

        repeat(3) { UnavailablePartLog.recordOnce("Test part A", failure) { written += it } }

        assertEquals(1, written.size)
    }

    @Test
    fun `a failing build hands its error to onFailure`() {
        var seen: Throwable? = null

        buildOptionalPart<String>("test", "Test part B", onFailure = { seen = it }) { throw failure }

        assertEquals(failure, seen)
    }
}
