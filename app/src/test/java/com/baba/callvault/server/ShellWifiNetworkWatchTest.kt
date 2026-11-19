/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

/**
 * Review N15: the recorder's Wi-Fi watch is an ordinary NetworkCallback, which Android keeps until it is
 * unregistered and calls on every network that comes up. (The PendingIntent form it replaces is released by
 * ConnectivityService 5 s after its first delivery: `onSendFinished` -> `releasePendingNetworkRequestWithDelay`.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShellWifiNetworkWatchTest {

    private val cm = ApplicationProvider.getApplicationContext<Application>().getSystemService(ConnectivityManager::class.java)
    private val shadow = shadowOf(cm)
    private val seen = mutableListOf<String>()
    private val watch = ShellWifiNetworkWatch(Handler(Looper.getMainLooper())) { cm }

    private fun network(id: Int): Network = ShadowNetwork.newInstance(id)

    @Test
    fun `one callback is registered, however often it is started`() {
        val before = shadow.networkCallbacks.size

        watch.start { seen += it }
        watch.start { seen += it }

        assertEquals(before + 1, shadow.networkCallbacks.size)
    }

    @Test
    fun `every join is delivered, not only the first`() {
        val before = shadow.networkCallbacks.toSet()
        watch.start { seen += it }
        val callback = (shadow.networkCallbacks - before).single()

        callback.onAvailable(network(100))
        callback.onAvailable(network(101))
        callback.onAvailable(network(102))

        assertEquals(3, seen.size)
        assertEquals(listOf("100", "101", "102"), seen)
    }

    @Test
    fun `stopping unregisters the callback, and a later start registers one again`() {
        val before = shadow.networkCallbacks.size
        watch.start { seen += it }

        watch.stop()
        assertEquals(before, shadow.networkCallbacks.size)

        watch.start { seen += it }
        assertEquals(before + 1, shadow.networkCallbacks.size)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `an unregister that fails keeps the callback, so a later start does not add a second one`() {
        val failing = mockk<ConnectivityManager>(relaxed = true)
        every { failing.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } throws RuntimeException("binder died")
        val w = ShellWifiNetworkWatch(Handler(Looper.getMainLooper())) { failing }
        w.start { }

        assertThrows(RuntimeException::class.java) { w.stop() }
        w.start { }

        verify(exactly = 1) { failing.registerNetworkCallback(any(), any<ConnectivityManager.NetworkCallback>(), any()) }
    }

    @Test
    fun `an unregister Android answers with 'not registered' forgets the callback`() {
        val cm2 = mockk<ConnectivityManager>(relaxed = true)
        every { cm2.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } throws IllegalArgumentException("not registered")
        val w = ShellWifiNetworkWatch(Handler(Looper.getMainLooper())) { cm2 }
        w.start { }

        w.stop()
        w.start { }

        verify(exactly = 2) { cm2.registerNetworkCallback(any(), any<ConnectivityManager.NetworkCallback>(), any()) }
    }

    @Test
    fun `the recorder's ConnectivityManager is built on the connectivity binder and presents the shell package`() {
        val built = ShellConnectivity.create()

        val context = ConnectivityManager::class.java.getDeclaredField("mContext").apply { isAccessible = true }.get(built) as android.content.Context
        assertEquals("com.android.shell", context.opPackageName)
        assertEquals("com.android.shell", context.packageName)
        assertNull(context.attributionTag)
    }
}
