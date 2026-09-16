/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * When an Intent means "open this recording", and when it only looks as though it does.
 *
 * Every case here is about a navigation happening that nobody asked for: the app jumping to a
 * recording days after the share that named it, or waiting for a row that can never arrive.
 */
class OpenRecordingRequestTest {

    private val name = "20260916_153631.988+0300_import_PTT-20260916-WA0007.opus"

    @Test
    fun `a freshly shared recording is opened`() {
        assertEquals(name, OpenRecordingRequest.fromIntentExtra(name, relaunchedFromHistory = false))
    }

    @Test
    fun `an Intent with no name asks for nothing`() {
        assertNull(OpenRecordingRequest.fromIntentExtra(null, relaunchedFromHistory = false))
    }

    @Test
    fun `reopening from the recents list does not re-open the recording`() {
        // The system re-delivers the task's original Intent, extras and all. Without the guard one
        // share would re-navigate on every later return to the app.
        assertNull(OpenRecordingRequest.fromIntentExtra(name, relaunchedFromHistory = true))
    }

    @Test
    fun `a blank name is nothing, not a name`() {
        // A name we cannot find leaves the app waiting for a row that will never arrive.
        assertNull(OpenRecordingRequest.fromIntentExtra("   ", relaunchedFromHistory = false))
        assertNull(OpenRecordingRequest.fromIntentExtra("", relaunchedFromHistory = false))
    }
}
