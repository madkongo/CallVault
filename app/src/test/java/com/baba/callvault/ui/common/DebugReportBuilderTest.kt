/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections

/**
 * The app report (which reads battery stats) and the system report run side by side only when the recorder is
 * connected. Without it both would go over the app's single ADB link, where one side's forceReconnect kills the
 * other's shell stream — so they run one after the other.
 */
class DebugReportBuilderTest {

    @Test
    fun `without a recorder the system report starts only after the app report`() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        runBlocking {
            DebugReportBuilder.build(
                recorderConnected = false,
                appReport = { events += "app start"; events += "app end"; null },
                systemReport = { events += "system start"; null },
            )
        }
        assertEquals(listOf("app start", "app end", "system start"), events)
    }

    @Test
    fun `with a recorder the two run side by side`() {
        val systemStarted = CompletableDeferred<Unit>()
        runBlocking {
            withTimeout(5_000) {
                DebugReportBuilder.build(
                    recorderConnected = true,
                    // Finishes only once the system report is running: would time out if they ran one after the other.
                    appReport = { systemStarted.await(); null },
                    systemReport = { systemStarted.complete(Unit); null },
                )
            }
        }
    }
}
