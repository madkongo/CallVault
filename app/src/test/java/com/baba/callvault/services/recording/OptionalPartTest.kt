/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The keep-alive must outlive a part it can do without. Its app-call detector threw NoClassDefFoundError on Android 11
 * (2026-11-20); the throw inside a runCatching was contained, but a later plain call to the same detector was not, and
 * that took the whole app down on every start: carrier recording with it.
 */
class OptionalPartTest {

    @Test
    fun `a part that throws an Error while being built is reported missing, not thrown`() {
        val part: String? = buildOptionalPart("test", "The detector") {
            throw NoClassDefFoundError("android/media/AudioManager\$OnModeChangedListener")
        }

        assertNull(part)
    }

    @Test
    fun `a part that builds is returned`() {
        assertEquals("built", buildOptionalPart("test", "The detector") { "built" })
    }
}
