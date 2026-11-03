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

class DriveCopyStopTest {

    @Test
    fun `a press stops the run that is going`() {
        DriveCopyStop.reset()
        DriveCopyStop.request()

        assertTrue(DriveCopyStop.isRequested())
    }

    @Test
    fun `an old press never stops the next run`() {
        DriveCopyStop.request()

        DriveCopyStop.reset() // every run starts with this

        assertFalse(DriveCopyStop.isRequested())
    }
}
