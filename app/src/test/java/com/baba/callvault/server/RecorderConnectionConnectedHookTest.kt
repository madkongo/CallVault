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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The keep-alive's "ready" notification hears about every recorder that connects, not only the ones it
 * started itself. Without this it said "starting up" for a minute after an update (OP9, 2026-11-03).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecorderConnectionConnectedHookTest {

    @After
    fun tearDown() {
        RecorderConnection.onConnected = null
        RecorderConnection.forceClear("test teardown")
    }

    @Test
    fun a_fresh_binder_tells_the_connected_hook() {
        var calls = 0
        RecorderConnection.onConnected = { calls++ }

        RecorderConnection.onBinderReceived(mockk(relaxed = true))

        assertEquals(1, calls)
    }

    @Test
    fun a_failing_hook_does_not_lose_the_binder() {
        RecorderConnection.onConnected = { error("boom") }

        RecorderConnection.onBinderReceived(mockk(relaxed = true))

        assertTrue(RecorderConnection.isConnected)
    }
}
