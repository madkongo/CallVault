/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review C51: linking the device and Drive periods copies the device period onto Drive, which can delete the only copy. */
class RetentionLinkingTest {

    @Test
    fun `Drive kept forever with a device period needs a confirmation`() = assertTrue(RetentionPolicy.linkingShortensDrive(30, 0))

    @Test
    fun `a longer Drive period shortened to the device one needs a confirmation`() = assertTrue(RetentionPolicy.linkingShortensDrive(30, 90))

    @Test
    fun `a device period of keep forever changes nothing on Drive`() {
        assertFalse(RetentionPolicy.linkingShortensDrive(0, 0))
        assertFalse(RetentionPolicy.linkingShortensDrive(0, 90))
    }

    @Test
    fun `a device period at or beyond the Drive one deletes no more than today`() {
        assertFalse(RetentionPolicy.linkingShortensDrive(90, 90))
        assertFalse(RetentionPolicy.linkingShortensDrive(180, 90))
    }
}
