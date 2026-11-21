package com.baba.callvault.ui.viewmodels

import com.baba.callvault.integrations.adb.UsbDebuggingState
import com.baba.callvault.integrations.adb.WifiState
import com.baba.callvault.services.recording.ReadinessNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BabApps item 115: Home said "Ready" while the recorder was down with no Wi-Fi and off-Wi-Fi recording off. */
class HomeStatusNeedsWifiTest {

    private fun notice(
        wifi: WifiState,
        usb: UsbDebuggingState = UsbDebuggingState.UNKNOWN,
        loopbackArmed: Boolean = false,
        offlineOn: Boolean = false,
    ) = ReadinessNotice.of(
        ready = false, recoveryStuck = false, usbDebugging = usb, wirelessDebuggingOn = false, wifi = wifi,
        loopbackArmed = loopbackArmed, hasGrant = true, wirelessDebuggingOffByUser = false, enforced = false,
        offlineRecordingOn = offlineOn,
    )

    @Test
    fun needsWifiComesBeforeReadyAndRecoveryStuck() {
        val order = HomeViewModel.HomeStatus.values().toList()
        assertTrue(order.indexOf(HomeViewModel.HomeStatus.NEEDS_WIFI) < order.indexOf(HomeViewModel.HomeStatus.READY))
        assertTrue(order.indexOf(HomeViewModel.HomeStatus.NEEDS_WIFI) < order.indexOf(HomeViewModel.HomeStatus.RECOVERY_STUCK))
        assertFalse(HomeViewModel.HomeStatus.NEEDS_WIFI.isReady)
    }

    private fun status(n: ReadinessNotice) = HomeViewModel.statusForNotice(n)

    @Test
    fun theReportedStateIsFlagged() {
        // A Pixel 8 tester's phone: no Wi-Fi, recorder down, off-Wi-Fi recording off, USB debugging unreadable (Android 17).
        val n = notice(WifiState.NOT_CONNECTED)
        assertEquals(ReadinessNotice.NEEDS_WIFI_TO_RESTART, n)
        assertEquals(HomeViewModel.HomeStatus.NEEDS_WIFI, status(n))
    }

    @Test
    fun usbDebuggingOffWithNoWifiIsFlaggedToo() {
        val n = notice(WifiState.NOT_CONNECTED, usb = UsbDebuggingState.OFF)
        assertEquals(HomeViewModel.HomeStatus.NEEDS_WIFI, status(n))
    }

    @Test
    fun noWifiIsFlaggedWhateverTheOffWifiSwitchSays() {
        // C97: with the switch ON the keep-alive still stands down (nothing to dial) and the notification says down.
        val n = notice(WifiState.NOT_CONNECTED, usb = UsbDebuggingState.OFF, offlineOn = true)
        assertEquals(ReadinessNotice.NEEDS_WIFI, n)
        assertEquals(HomeViewModel.HomeStatus.NEEDS_WIFI, status(n))
        assertEquals(HomeViewModel.HomeStatus.NEEDS_WIFI, status(ReadinessNotice.NEEDS_WIFI_TO_RESTART))
    }

    @Test
    fun wirelessDebuggingTurnedOffByTheUserIsDownNotReady() {
        assertEquals(HomeViewModel.HomeStatus.WD_OFF_BY_USER, status(ReadinessNotice.WD_OFF_BY_USER))
        assertFalse(HomeViewModel.HomeStatus.WD_OFF_BY_USER.isReady)
    }

    @Test
    fun wifiUpIsNotFlagged() {
        assertEquals(null, status(notice(WifiState.CONNECTED)))
    }

    @Test
    fun unreadableWifiIsNotEvidence() {
        assertEquals(null, status(notice(WifiState.UNKNOWN)))
    }

    @Test
    fun noticesThatSayStartingOrReadyAreNotFlagged() {
        for (n in listOf(ReadinessNotice.READY, ReadinessNotice.READY_OFFLINE_PAUSED, ReadinessNotice.STARTING)) {
            assertEquals("$n", null, status(n))
        }
    }
}
