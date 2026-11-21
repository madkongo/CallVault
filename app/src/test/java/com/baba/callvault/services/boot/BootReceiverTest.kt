/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.boot

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.PrivilegedMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review C57: the boot receiver starts three services, and the system may refuse a start (a background-start limit,
 * or a boot action sent by something other than the system). One refusal must not crash the receiver or skip the other two.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BootReceiverTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private class RefusingContext(base: Context) : ContextWrapper(base) {
        val asked = mutableListOf<String>()
        override fun startForegroundService(service: Intent): ComponentName? {
            asked += service.component?.className.orEmpty()
            throw IllegalStateException("startForegroundService() not allowed due to mAllowStartForeground false")
        }
        override fun startService(service: Intent): ComponentName? {
            asked += service.component?.className.orEmpty()
            throw IllegalStateException("not allowed")
        }
    }

    @Test
    fun `a refused service start does not crash the receiver or stop the others being tried`() {
        AppPreferences(app).setPrivilegedMode(PrivilegedMode.SHIZUKU)
        val context = RefusingContext(app)

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertTrue("the connection service was tried", context.asked.any { it.endsWith("AdbConnectionService") })
        assertTrue("the call monitor was still tried", context.asked.any { it.endsWith("CallMonitorService") })
        assertTrue("the keep-alive was still tried", context.asked.any { it.endsWith("DaemonKeepAliveService") })
    }

    private fun receiverAt(uptimeMs: Long) = BootReceiver().also { it.uptimeMs = { uptimeMs } }

    private fun askedAfter(action: String, uptimeMs: Long): List<String> {
        AppPreferences(app).setPrivilegedMode(PrivilegedMode.SHIZUKU)
        val context = RefusingContext(app)
        receiverAt(uptimeMs).onReceive(context, Intent(action))
        return context.asked
    }

    @Test
    fun `a real BOOT_COMPLETED runs the boot path whatever the uptime`() {
        // A very slow phone, or a work profile unlocking late: only the system can send this action.
        assertTrue(askedAfter(Intent.ACTION_BOOT_COMPLETED, uptimeMs = 3 * 60 * 60 * 1000L).any { it.endsWith("DaemonKeepAliveService") })
        assertTrue(askedAfter(Intent.ACTION_BOOT_COMPLETED, uptimeMs = 5_000L).any { it.endsWith("DaemonKeepAliveService") })
    }

    @Test
    fun `QUICKBOOT_POWERON shortly after boot runs the boot path`() {
        assertTrue(askedAfter("android.intent.action.QUICKBOOT_POWERON", uptimeMs = 60_000L).any { it.endsWith("DaemonKeepAliveService") })
    }

    @Test
    fun `QUICKBOOT_POWERON long after boot is ignored`() {
        // Anyone can send it; with the phone up for hours it is not a boot.
        assertTrue(askedAfter("android.intent.action.QUICKBOOT_POWERON", uptimeMs = 3 * 60 * 60 * 1000L).isEmpty())
        assertFalse(BootReceiver.isGenuineBoot("android.intent.action.QUICKBOOT_POWERON", BootReceiver.FAKE_BOOT_UPTIME_LIMIT_MS))
        assertTrue(BootReceiver.isGenuineBoot("android.intent.action.QUICKBOOT_POWERON", BootReceiver.FAKE_BOOT_UPTIME_LIMIT_MS - 1))
    }

    @Test
    fun `other actions are never a boot`() {
        assertFalse(BootReceiver.isGenuineBoot("android.intent.action.MY_PACKAGE_REPLACED", uptimeMs = 1_000L))
    }
}
