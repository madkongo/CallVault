/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baba.callvault.server.ShizukuBackend
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That CallVault really does put somebody else's Shizuku server back after killing it.
 *
 * Only a device can answer this. The decisions are unit-tested in `ShizukuHealPolicyTest`, but the part
 * that matters — that an `adbd` restart kills the server, that the starter inside the manager's install
 * directory can be found and run over CallVault's own shell, and that a server answers again afterwards
 * — is the phone's behaviour, not ours. It is driven from here rather than through the UI because the
 * OP9 sits behind a lock screen.
 *
 * **Skips itself, loudly, when there is no Shizuku server running.** That is the honest answer on a
 * phone without one: there is nothing to kill and nothing to heal, and asserting anything would only
 * measure the skip.
 *
 * It leaves the loopback listener closed, which is how a phone without off-Wi-Fi recording starts.
 */
@RunWith(AndroidJUnit4::class)
class ShizukuHealDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun arming_off_wifi_recording_starts_shizuku_again() {
        if (!running()) { logSkip("arming"); return }
        try {
            val armed = AdbShell.armLoopbackIfNeededWithReason(context)
            Log.i(TAG, "arm result: $armed")
            assertBackUp("arming off-Wi-Fi recording")
        } finally {
            // Only the tests that armed close the listener again. Closing one this run did not open
            // restarts adbd for nothing — and on a device reached over adbd that ends the session, which
            // is how the first run of this file took the emulator's transport with it.
            closeTheListener()
        }
    }

    @Test
    fun closing_the_off_wifi_listener_starts_shizuku_again() {
        if (!running()) { logSkip("closing the listener"); return }
        // Arm first so there is something to close; its own heal has to finish before the next restart,
        // or the second measurement is really the first one's tail.
        AdbShell.armLoopbackIfNeededWithReason(context)
        assertBackUp("the setup arm")

        closeTheListener()
        assertBackUp("closing the off-Wi-Fi listener")
    }

    private fun closeTheListener() {
        runCatching { AdbShell.disarmLoopback(context) }
            .onFailure { Log.w(TAG, "could not close the listener: ${it.message}") }
    }

    /**
     * The heal on its own, with the `adbd` restart taken out.
     *
     * The operator stops the server first (`adb shell kill $(pidof shizuku_server)`), which is what the
     * restart does to it, and this stands in for the churn site. It exists because the two tests above
     * cannot run everywhere: arming and closing the listener restart `adbd`, and on a device reached
     * *over* `adbd` — an emulator, or a phone on wireless debugging — that takes the transport with them.
     * This one exercises exactly the same path from [ShizukuHealPolicy] down.
     */
    @Test
    fun a_server_stopped_outside_the_app_is_started_again() {
        if (running()) {
            Log.w(TAG, "A Shizuku server is answering; stop it first or this measures nothing.")
            return
        }
        ShizukuRestarter.healAfterAdbdRestart(context, "a stand-in for the adbd restart", underDialog = false)
        assertBackUp("the heal on its own")
    }

    /**
     * The guard against two privileged hosts: a server that survived, or restarted itself, is not
     * started a second time.
     *
     * The operator confirms the other half from outside — `pidof shizuku_server` must read the same
     * number before and after — because an app cannot see another uid's processes.
     */
    @Test
    fun a_restart_the_server_survived_starts_nothing() {
        if (!running()) {
            Log.w(TAG, "No Shizuku server is answering; the leave-it-alone guard has nothing to guard.")
            return
        }
        AdbdChurnNotice.around(context, "a restart Shizuku survived") { }
        Thread.sleep(SETTLE_MS)
        assertTrue("the server that survived must still be the one answering", running())
    }

    /** A phone with no Shizuku running is not a phone CallVault starts one on. */
    @Test
    fun a_restart_with_no_shizuku_running_starts_nothing() {
        if (running()) {
            Log.w(TAG, "A Shizuku server is answering; stop it first or this measures nothing.")
            return
        }
        AdbdChurnNotice.around(context, "a restart with no Shizuku running") { }
        Thread.sleep(SETTLE_MS)
        assertFalse("nothing may start a server nobody had running", running())
    }

    private fun assertBackUp(what: String) {
        val startedAt = System.currentTimeMillis()
        val back = waitForShizuku()
        Log.i(TAG, "after $what, Shizuku answering = $back (waited ${System.currentTimeMillis() - startedAt}ms)")
        assertTrue("Shizuku must be answering again after $what", back)
    }

    /** The heal runs on its own thread behind the ADB lock, so the answer is not immediate. */
    private fun waitForShizuku(): Boolean {
        val deadline = System.currentTimeMillis() + HEAL_BUDGET_MS
        while (System.currentTimeMillis() < deadline) {
            if (running()) return true
            Thread.sleep(500)
        }
        return false
    }

    private fun running() = ShizukuBackend.isRunning()

    private fun logSkip(what: String) {
        Log.w(TAG, "No Shizuku server is running; $what has nothing to heal. Start Shizuku and run again.")
    }

    private companion object {
        const val TAG = "CV:ShizukuHealTest"

        /** Generous: the heal queues behind the ADB work it must never get in front of. */
        const val HEAL_BUDGET_MS = 60_000L

        /** Long enough for a heal that should not happen to have happened. */
        const val SETTLE_MS = 5_000L
    }
}
