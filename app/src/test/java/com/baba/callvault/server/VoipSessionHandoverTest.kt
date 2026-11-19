/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review C49: the app's first answer-hold pause can arrive on a binder thread while the session is still
 * starting on the worker. The session must be reachable by then, or the pause is dropped as "no VoIP session".
 */
class VoipSessionHandoverTest {

    @Test
    fun `the session is published before it starts`() {
        val events = mutableListOf<String>()
        VoipSessionHandover.publishThenStart(
            "session",
            publish = { events += "publish $it" },
            start = { events += "start $it" },
        )
        assertEquals(listOf("publish session", "start session"), events)
    }

    @Test
    fun `a session that fails to start is withdrawn and the failure goes on`() {
        val events = mutableListOf<String>()
        val thrown = runCatching {
            VoipSessionHandover.publishThenStart(
                "session",
                publish = { events += "publish $it" },
                start = { throw IllegalStateException("no mic") },
            )
        }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertEquals(listOf("publish session", "publish null"), events)
    }
}
