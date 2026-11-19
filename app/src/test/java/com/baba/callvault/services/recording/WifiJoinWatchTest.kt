/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.PrivilegedMode
import com.baba.callvault.server.ApprovalConfig
import com.baba.callvault.server.ApprovalConfigBundle
import com.baba.callvault.server.IRecorderService
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.server.WifiJoinEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Review N15, app side: the app arms the recorder's own Wi-Fi join watch (on every recorder connection, so a
 * recorder started again watches again) and writes the recorder's lines into the always-on log.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WifiJoinWatchTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = AppPreferences(context)
    private val now = System.currentTimeMillis()

    /** A recorder that remembers how it was armed and answers with [pending] lines once. */
    private class FakeRecorder(private val watching: Boolean = true) : IRecorderService.Default() {
        val arms = mutableListOf<Pair<Boolean, Set<String>>>()
        val salts = mutableListOf<String>()
        var pending = arrayOf<String>()
        val armed = CountDownLatch(1)
        override fun armWifiJoinWatch(on: Boolean, knownKeys: Array<String>?, salt: String?): Boolean {
            arms += on to knownKeys.orEmpty().toSet()
            salts += salt.orEmpty()
            armed.countDown()
            return on && watching
        }
        override fun drainWifiJoinEvents(): Array<String> = pending.also { pending = arrayOf() }
    }

    private var fallbacks = 0
    private fun sync(recorder: IRecorderService?) =
        WifiJoinWatch.syncNow(context, recorder, fallback = { fallbacks++ }, nowMs = now)

    @Before
    fun setUp() {
        prefs.setPrivilegedMode(PrivilegedMode.SHIZUKU)
        prefs.setWifiNetworkAutoApproveEnabled(true)
    }

    @After
    fun tearDown() {
        RecorderConnection.forceClear("test end")
    }

    @Test
    fun `Shizuku mode with the setting on arms the recorder with the app's keys and salt`() {
        prefs.addApprovedAccessPointKey("k0", now)
        val recorder = FakeRecorder()

        assertEquals(WifiJoinWatch.SyncResult.WATCHING, sync(recorder))

        assertEquals(listOf(true to setOf("k0")), recorder.arms)
        assertEquals(listOf(prefs.getAccessPointKeySalt()), recorder.salts)
        assertEquals(0, fallbacks)
    }

    @Test
    fun `the setting off, or another mode, disarms it`() {
        val recorder = FakeRecorder()
        prefs.setWifiNetworkAutoApproveEnabled(false)
        assertEquals(WifiJoinWatch.SyncResult.NOT_WANTED, sync(recorder))

        prefs.setWifiNetworkAutoApproveEnabled(true)
        prefs.setPrivilegedMode(PrivilegedMode.STANDALONE)
        assertEquals(WifiJoinWatch.SyncResult.NOT_WANTED, sync(recorder))

        assertEquals(listOf(false, false), recorder.arms.map { it.first })
        assertEquals(0, fallbacks)
    }

    @Test
    fun `no recorder yet - nothing armed, nothing thrown, armed later on connection`() {
        assertEquals(WifiJoinWatch.SyncResult.NO_RECORDER, sync(null))
        assertEquals(0, fallbacks)
    }

    @Test
    fun `a recorder that cannot watch falls back to one check from the app, and says so in the always-on log`() {
        val recorder = FakeRecorder(watching = false)

        assertEquals(WifiJoinWatch.SyncResult.FALLBACK, sync(recorder))

        assertEquals(1, fallbacks)
    }

    @Test
    fun `the recorder's lines are recorded, and a trusted access point's key is remembered`() {
        val recorder = FakeRecorder().apply {
            pending = arrayOf(
                WifiJoinEvent(now, false, "watching Wi-Fi joins in the recorder (pid 7)").encode(),
                WifiJoinEvent(now, false, "approved access point **:**:**:**:88:01 (always allow)", trustedKey = "knew").encode(),
            )
        }

        sync(recorder)

        assertTrue("knew" in prefs.getApprovedAccessPointKeys(now))
    }

    @Test
    fun `a line delivered late carries the time the recorder saw it`() {
        val late = WifiJoinEvent(now - 120_000L, false, "Wi-Fi network available (net 101)")

        val line = WifiJoinEvents.lineFor(late, now)

        assertTrue(line, line.startsWith("Wi-Fi approval (Shizuku): Wi-Fi network available (net 101) (in the recorder at "))
        assertEquals("Wi-Fi approval (Shizuku): x", WifiJoinEvents.lineFor(WifiJoinEvent(now - 1_000L, false, "x"), now))
    }

    @Test
    fun `a fresh recorder binder arms the watch again`() {
        // The recorder died and a new one connected (crash, update, reboot): the app's connection hook re-arms it.
        val restarted = FakeRecorder()

        RecorderConnection.onBinderReceived(restarted)

        assertTrue("not armed on connection", restarted.armed.await(5, TimeUnit.SECONDS))
        assertEquals(true, restarted.arms.first().first)
    }

    @Test
    fun `the recorder's question before an approval is answered from the settings now`() {
        prefs.addApprovedAccessPointKey("k0", now)
        val on = ApprovalConfigBundle.read(ApprovalConfigBundle.write(WifiJoinWatch.approvalConfig(context, now)))
        assertEquals(ApprovalConfig(true, setOf("k0"), prefs.getAccessPointKeySalt()), on)

        prefs.setWifiNetworkAutoApproveEnabled(false)
        assertEquals(false, ApprovalConfigBundle.read(ApprovalConfigBundle.write(WifiJoinWatch.approvalConfig(context, now)))?.wanted)

        prefs.setWifiNetworkAutoApproveEnabled(true)
        prefs.setPrivilegedMode(PrivilegedMode.STANDALONE)
        assertEquals(false, WifiJoinWatch.approvalConfig(context, now).wanted)
    }

    @Test
    fun `no answer, or an answer without the field, is not a confirmation`() {
        assertNull(ApprovalConfigBundle.read(null))
        assertNull(ApprovalConfigBundle.read(android.os.Bundle()))
    }

    @Test
    fun `lines from the recorder are capped and kept on one line each`() {
        val long = WifiJoinEvent(now, false, "a\nb\r\nc" + "x".repeat(2_000))
        val line = WifiJoinEvents.lineFor(long, now)
        assertTrue(line.startsWith("Wi-Fi approval (Shizuku): a b c"))
        assertTrue(line.length <= WifiJoinEvents.PREFIX.length + WifiJoinEvents.MAX_CHARS)
        assertTrue('\n' !in line)

        // Only the first 64 lines of a hand-over are taken: a key on line 100 is never read.
        val flood = Array(500) { WifiJoinEvent(now, false, "line $it", trustedKey = if (it == 100) "k100" else "").encode() }
        WifiJoinEvents.record(context, flood, now)
        assertTrue("k100" !in prefs.getApprovedAccessPointKeys(now))

        WifiJoinEvents.record(context, arrayOf(WifiJoinEvent(now, false, "x", trustedKey = "bad key\n").encode()), now)
        assertTrue("bad key\n" !in prefs.getApprovedAccessPointKeys(now))
    }
}
