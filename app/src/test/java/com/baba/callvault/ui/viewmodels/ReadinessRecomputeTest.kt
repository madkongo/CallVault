/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.viewmodels

import com.baba.callvault.server.IRecorderService
import com.baba.callvault.server.RecorderConnection
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Review C24: Home's "Needs Wi-Fi to record" card was computed once and stayed over a recorder that connected
 * afterwards. Home now recomputes on [ReadinessRecompute.triggers]; this pins that a binder arriving, with no
 * resume, is such a trigger, and so is Wi-Fi changing.
 */
class ReadinessRecomputeTest {

    private val fakeService: IRecorderService = Proxy.newProxyInstance(
        IRecorderService::class.java.classLoader, arrayOf(IRecorderService::class.java),
    ) { _, _, _ -> null } as IRecorderService

    @After
    fun tearDown() = RecorderConnection.forceClear("test teardown")

    @Test
    fun `a binder arriving recomputes the status without a resume, and the card follows it`() = runTest(UnconfinedTestDispatcher()) {
        RecorderConnection.forceClear("test start")
        // What Home shows, recomputed the way HomeViewModel does it, from the connection alone here.
        fun status() = if (RecorderConnection.isConnected) HomeViewModel.HomeStatus.READY else HomeViewModel.HomeStatus.NEEDS_WIFI
        var shown = status()
        val job = launch { ReadinessRecompute.triggers(RecorderConnection.connected, emptyFlow()).collect { shown = status() } }
        assertEquals(HomeViewModel.HomeStatus.NEEDS_WIFI, shown)

        RecorderConnection.onBinderReceived(fakeService)
        assertEquals(HomeViewModel.HomeStatus.READY, shown)

        RecorderConnection.forceClear("recorder dropped")
        assertEquals(HomeViewModel.HomeStatus.NEEDS_WIFI, shown)
        job.cancel()
    }

    @Test
    fun `Wi-Fi coming or going recomputes too, and the current connection value does not`() = runTest(UnconfinedTestDispatcher()) {
        RecorderConnection.forceClear("test start")
        val wifi = MutableSharedFlow<Unit>()
        var recomputes = 0
        val job = launch { ReadinessRecompute.triggers(RecorderConnection.connected, wifi).collect { recomputes++ } }
        assertEquals(0, recomputes)
        wifi.emit(Unit)
        wifi.emit(Unit)
        assertTrue(recomputes == 2)
        job.cancel()
    }
}
