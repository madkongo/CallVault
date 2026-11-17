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

    @Test
    fun theReportedStateIsFlagged() {
        // A Pixel 8 tester's phone: no Wi-Fi, recorder down, off-Wi-Fi recording off, USB debugging unreadable (Android 17).
        val n = notice(WifiState.NOT_CONNECTED)
        assertEquals(ReadinessNotice.NEEDS_WIFI_TO_RESTART, n)
        assertTrue(ReadinessNotice.blocksRecordingOffWifi(n, offlineRecordingOn = false))
    }

    @Test
    fun usbDebuggingOffWithNoWifiIsFlaggedToo() {
        val n = notice(WifiState.NOT_CONNECTED, usb = UsbDebuggingState.OFF)
        assertTrue(ReadinessNotice.blocksRecordingOffWifi(n, offlineRecordingOn = false))
    }

    @Test
    fun wifiUpIsNotFlagged() {
        assertFalse(ReadinessNotice.blocksRecordingOffWifi(notice(WifiState.CONNECTED), false))
    }

    @Test
    fun unreadableWifiIsNotEvidence() {
        assertFalse(ReadinessNotice.blocksRecordingOffWifi(notice(WifiState.UNKNOWN), false))
    }

    @Test
    fun offWifiRecordingOnIsNotFlagged() {
        val n = notice(WifiState.NOT_CONNECTED, offlineOn = true)
        assertFalse(ReadinessNotice.blocksRecordingOffWifi(n, offlineRecordingOn = true))
    }

    @Test
    fun otherNoticesAreNotFlagged() {
        for (n in ReadinessNotice.values()) {
            if (n == ReadinessNotice.NEEDS_WIFI || n == ReadinessNotice.NEEDS_WIFI_TO_RESTART) continue
            assertFalse("$n", ReadinessNotice.blocksRecordingOffWifi(n, false))
        }
    }
}
