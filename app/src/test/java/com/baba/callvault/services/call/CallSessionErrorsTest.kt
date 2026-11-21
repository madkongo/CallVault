/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.integrations.adb.UsbDebuggingEvents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** C40: an exception nobody caught in the call-session scope is logged, never a crash. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallSessionErrorsTest {

    private val app: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `an uncaught failure is written to the always-on log with where it happened`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CallSessionErrors.handler(app, "session update"))
        val ran = CompletableDeferred<Unit>()
        scope.launch {
            ran.complete(Unit)
            throw IllegalStateException("metadata missing")
        }
        ran.await()
        val text = UsbDebuggingEvents.readForReport(app).orEmpty()
        assertTrue(text, text.contains("session update") && text.contains("metadata missing"))
    }

    @Test
    fun `the scope stays usable after a failure`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CallSessionErrors.handler(app, "x"))
        scope.launch { throw IllegalStateException("boom") }
        val next = CompletableDeferred<Int>()
        scope.launch { next.complete(7) }
        assertTrue(next.await() == 7)
    }
}
