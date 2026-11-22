/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A restarted process that held no recorder must not count a cleanup as a recorder death (2026-11-22). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecorderConnectionDeathFlagTest {

    @Before
    fun setUp() {
        RecorderConnection.forceClear("test")
        RecorderConnection.resetDeathFlagForTest()
    }

    @After
    fun tearDown() {
        RecorderConnection.forceClear("test")
        RecorderConnection.resetDeathFlagForTest()
    }

    @Test
    fun `a death report with no binder held does not set the flag`() {
        RecorderConnection.onBinderDied()
        assertFalse(RecorderConnection.sawRecorderDie)
    }

    @Test
    fun `a held binder that died sets the flag`() {
        RecorderConnection.onBinderReceived(mockk(relaxed = true))
        RecorderConnection.onBinderDied()
        assertTrue(RecorderConnection.sawRecorderDie)
        assertFalse(RecorderConnection.isConnected)
    }
}
