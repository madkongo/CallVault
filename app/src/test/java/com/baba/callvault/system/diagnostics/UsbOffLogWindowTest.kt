package com.baba.callvault.system.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class UsbOffLogWindowTest {

    private val zone = ZoneId.of("UTC")
    private val event = LocalDateTime.of(2026, 11, 4, 9, 31, 4, 453_000_000).atZone(zone).toInstant().toEpochMilli()

    private val log = sequenceOf(
        "11-04 09:30:45.000  2250  2767 D UsbDeviceManager: too early",
        "11-04 09:31:04.419  2250  4272 I OplusStatusBarManagerServiceEnhance:  getTopIsFullscreen false",
        "11-04 09:31:04.453  2250  2767 D UsbDeviceManager: setAdbEnabled: false",
        "11-04 09:31:04.470  5555  5555 W Settings: Setting adb_enabled has moved from android.provider.Settings.Secure",
        "11-04 09:31:05.000  3000  3000 I SomeBank: user opened the app",
        "11-04 09:31:05.100  3902  3902 I SystemUi--Statusbar: CollapsedSbFragment visibilities",
        "11-04 09:31:05.200 28301 28301 I adbd    : adbd started",
        "11-04 09:31:06.000  2250  2767 D AdbDebuggingManager: Read failed",
        "11-04 09:31:20.000  2250  2767 D UsbDeviceManager: too late",
        "not a log line about adb",
    )

    @Test fun `keeps only debugging and usb lines inside the window`() {
        val kept = UsbOffLogWindow.select(log, event, zone)
        assertEquals(4, kept.size)
        assertEquals(true, kept[0].contains("setAdbEnabled"))
        assertEquals(true, kept[1].contains("Setting adb_enabled"))
        assertEquals(true, kept[2].contains("adbd started"))
        assertEquals(true, kept[3].contains("AdbDebuggingManager"))
    }

    @Test fun `StatusBar is not mistaken for usb`() {
        val kept = UsbOffLogWindow.select(log, event, zone)
        assertEquals(false, kept.any { it.contains("StatusBar") || it.contains("Statusbar") })
    }

    @Test fun `start stamp is fifteen seconds before in logcat format`() =
        assertEquals("11-04 09:30:49.453", UsbOffLogWindow.startStamp(event, zone))

    @Test fun `long lines are cut`() {
        val long = sequenceOf("11-04 09:31:04.500  2250  2767 D UsbDeviceManager: " + "x".repeat(1000))
        assertEquals(UsbOffLogWindow.MAX_LINE_CHARS, UsbOffLogWindow.select(long, event, zone).single().length)
    }

    @Test fun `pids are joined to process names from ps`() {
        val kept = UsbOffLogWindow.select(log, event, zone)
        val pids = UsbOffLogWindow.pidsOf(kept)
        assertEquals(setOf(2250, 5555, 28301), pids)
        val ps = """
            USER           PID ARGS
            system        2250 system_server
            u0_a120       5555 com.example.security
            u0_a1         9999 other
        """.trimIndent()
        assertEquals(
            mapOf(2250 to "system_server", 5555 to "com.example.security"),
            UsbOffLogWindow.processNames(ps, pids),
        )
    }

    @Test fun `caps the number of lines`() {
        val many = generateSequence { "11-04 09:31:04.500  2250  2767 D UsbDeviceManager: x" }.take(500)
        assertEquals(UsbOffLogWindow.MAX_LINES, UsbOffLogWindow.select(many, event, zone).size)
    }
}
