package com.baba.callvault.system.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogcatHealthTest {

    private val raw = """
        --------- beginning of main
        11-01 11:44:03.805  2079  2079 I AudioFlinger: boot
        11-04 13:31:28.900  1500  1600 D UsbDeviceManager: setAdbEnabled: false
        11-04 13:31:29.000  3902  3902 I SystemUi--Statusbar: noise
        11-04 15:46:04.000   627   627 I adbd    : adbd started
    """.trimIndent()

    @Test fun `says how old and how new the log is`() {
        val text = LogcatHealth.render(raw, "main: ring buffer is 16 MiB\nsystem: ring buffer is 4 MiB")
        assertTrue(text.contains("Lines read: 4. Oldest: 11-01 11:44:03. Newest: 11-04 15:46:04."))
        assertTrue(text.contains("system: ring buffer is 4 MiB"))
    }

    @Test fun `keeps only debugging and usb lines`() =
        assertEquals(
            listOf("UsbDeviceManager", "adbd"),
            LogcatHealth.debuggingLines(raw).map { if (it.contains("Usb")) "UsbDeviceManager" else "adbd" },
        )
}
