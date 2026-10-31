/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.media.AudioManager
import com.baba.callvault.services.recording.WifiCallRoute.Reason
import com.baba.callvault.services.recording.WifiCallRoute.Route
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every input of the Wi-Fi-call route decision, one at a time from the case that switches. */
class WifiCallRouteTest {

    /** A Samsung Wi-Fi call, everything ready: the one case that switches. */
    private val wifiCall = WifiCallRoute.Inputs(
        mode = AudioManager.MODE_IN_COMMUNICATION,
        managedCall = true,
        voiceCallPlayers = 1,
        voipArmed = true,
        daemonConnected = true,
        alreadySwitched = false,
        appCallHeld = false,
        forced = false,
    )

    private fun decide(inputs: WifiCallRoute.Inputs) = WifiCallRoute.decide(inputs)

    @Test
    fun `a Wi-Fi call with everything ready goes to the app-call mix`() {
        assertEquals(WifiCallRoute.Decision(Route.APP_CALL_MIX, Reason.WIFI_CALL), decide(wifiCall))
    }

    @Test
    fun `an ordinary modem call in IN_CALL stays on the call source`() {
        val d = decide(wifiCall.copy(mode = AudioManager.MODE_IN_CALL, voiceCallPlayers = 0))
        assertEquals(Route.CALL_SOURCE, d.route)
        assertEquals(Reason.MODE_NOT_IN_COMMUNICATION, d.reason)
    }

    @Test
    fun `IN_CALL with a voice player still stays on the call source`() {
        assertEquals(Route.CALL_SOURCE, decide(wifiCall.copy(mode = AudioManager.MODE_IN_CALL)).route)
    }

    @Test
    fun `normal and ringtone modes stay on the call source`() {
        listOf(AudioManager.MODE_NORMAL, AudioManager.MODE_RINGTONE).forEach { mode ->
            assertEquals(Route.CALL_SOURCE, decide(wifiCall.copy(mode = mode)).route)
        }
    }

    @Test
    fun `IN_COMMUNICATION without a voice player stays on the call source`() {
        val d = decide(wifiCall.copy(voiceCallPlayers = 0))
        assertEquals(Route.CALL_SOURCE, d.route)
        assertEquals(Reason.NO_VOICE_COMM_PLAYER, d.reason)
    }

    @Test
    fun `no managed call means this is not a carrier call`() {
        val d = decide(wifiCall.copy(managedCall = false))
        assertEquals(Route.CALL_SOURCE, d.route)
        assertEquals(Reason.NOT_MANAGED, d.reason)
    }

    @Test
    fun `app-call recording off stays on the call source`() {
        val d = decide(wifiCall.copy(voipArmed = false))
        assertEquals(Route.CALL_SOURCE, d.route)
        assertEquals(Reason.APP_CALLS_OFF, d.reason)
    }

    @Test
    fun `daemon down stays on the call source`() {
        val d = decide(wifiCall.copy(daemonConnected = false))
        assertEquals(Route.CALL_SOURCE, d.route)
        assertEquals(Reason.DAEMON_DOWN, d.reason)
    }

    @Test
    fun `an app call held behind this phone call keeps the call source`() {
        val d = decide(wifiCall.copy(appCallHeld = true))
        assertEquals(Route.CALL_SOURCE, d.route)
        assertEquals(Reason.APP_CALL_HELD, d.reason)
    }

    @Test
    fun `the latch never switches twice`() {
        val d = decide(wifiCall.copy(alreadySwitched = true))
        assertEquals(Route.CALL_SOURCE, d.route)
        assertEquals(Reason.ALREADY_SWITCHED, d.reason)
    }

    @Test
    fun `the latch wins over the debug force`() {
        assertEquals(Route.CALL_SOURCE, decide(wifiCall.copy(alreadySwitched = true, forced = true)).route)
    }

    @Test
    fun `the debug force skips the mode and player checks`() {
        val d = decide(wifiCall.copy(mode = AudioManager.MODE_IN_CALL, voiceCallPlayers = 0, forced = true))
        assertEquals(WifiCallRoute.Decision(Route.APP_CALL_MIX, Reason.FORCED), d)
    }

    @Test
    fun `the debug force still needs a carrier call, app calls on and a daemon`() {
        val forced = wifiCall.copy(mode = AudioManager.MODE_IN_CALL, voiceCallPlayers = 0, forced = true)
        assertEquals(Route.CALL_SOURCE, decide(forced.copy(managedCall = false)).route)
        assertEquals(Route.CALL_SOURCE, decide(forced.copy(voipArmed = false)).route)
        assertEquals(Route.CALL_SOURCE, decide(forced.copy(daemonConnected = false)).route)
    }

    @Test
    fun `full truth table switches in exactly one row`() {
        val bools = listOf(false, true)
        val modes = listOf(AudioManager.MODE_NORMAL, AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION)
        var switching = 0
        for (mode in modes) for (managed in bools) for (players in listOf(0, 2)) for (armed in bools)
            for (daemon in bools) for (switched in bools) for (held in bools) {
                val d = decide(
                    WifiCallRoute.Inputs(mode, managed, players, armed, daemon, switched, held, forced = false)
                )
                if (d.route == Route.APP_CALL_MIX) {
                    switching++
                    assertEquals(AudioManager.MODE_IN_COMMUNICATION, mode)
                    assert(managed && players > 0 && armed && daemon && !switched && !held)
                }
            }
        assertEquals(1, switching)
    }

    @Test
    fun `a change is only worth logging when the decision or what it saw changed`() {
        val a = WifiCallRoute.Observation(decide(wifiCall), "mode=IN_COMMUNICATION vcPlay=1")
        assertEquals(false, WifiCallRoute.isWorthLogging(a, a.copy()))
        assertEquals(true, WifiCallRoute.isWorthLogging(null, a))
        assertEquals(true, WifiCallRoute.isWorthLogging(a, a.copy(seen = "mode=NORMAL vcPlay=0")))
    }
}
