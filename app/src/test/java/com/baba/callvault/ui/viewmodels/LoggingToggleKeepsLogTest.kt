/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.viewmodels

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.server.RecorderBackend
import com.baba.callvault.utils.AppLogger
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Switching debug logging on must not delete what was already captured.
 *
 * It used to, silently: a reporter (S24 Ultra, 2026-11-08) had logging on before his phone got slow, flipped the
 * switch again before exporting, and sent a report that started 3 minutes earlier — the slow period was gone.
 * Deleting is what the Delete button is for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LoggingToggleKeepsLogTest {

    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        mockkObject(AppLogger, RecorderBackend)
        viewModel = SettingsViewModel(ApplicationProvider.getApplicationContext<Application>())
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `switching logging on keeps the log already captured`() {
        viewModel.setLoggingEnabled(true)

        verify(exactly = 0) { AppLogger.clearLogs() }
        verify(exactly = 0) { RecorderBackend.clearHostDiagnostics() }
    }

    @Test
    fun `switching logging off and on again keeps it too`() {
        viewModel.setLoggingEnabled(false)
        viewModel.setLoggingEnabled(true)

        verify(exactly = 0) { AppLogger.clearLogs() }
        verify(exactly = 0) { RecorderBackend.clearHostDiagnostics() }
    }
}
