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

    private val ours = SystemLogFilter.Ours("com.baba.callvault", 10024)

    @Test fun `keeps only debugging and usb lines`() =
        assertEquals(
            listOf("UsbDeviceManager", "adbd"),
            LogcatHealth.debuggingLines(raw.lines(), ours).map { if (it.contains("Usb")) "UsbDeviceManager" else "adbd" },
        )

    // The vivo report of 2026-11-20 listed other apps' lines here because they happened to say "debuggable" or
    // "developers" — a codec, a libc probe, a settings read — which is other apps' content in a public report.
    @Test fun `drops other apps' lines that merely contain a keyword`() {
        val others = listOf(
            "11-20 17:36:03.681 42896 25634 W libc    : Access denied finding property \"ro.debuggable\"",
            "11-20 17:42:41.177 31515 22626 W CCodec  : It is recommended that app developers double check the max input size",
            "11-20 17:36:06.225 17265 17265 W libbinder.BackendUnifiedServiceManager: Cannot cache binder. serviceName: usb",
            "11-20 17:36:07.000 31515 31515 I SomeApp : usb-ffs mounted for my debugger",
        )
        assertEquals(emptyList<String>(), LogcatHealth.debuggingLines(others, ours))
    }

    @Test fun `keeps our own lines, the debugging services, and any line naming a debugging switch`() {
        val keep = listOf(
            "11-20 17:57:33.823 38978 39228 I CV:AdbShell: Connected over loopback tcpip :5555",
            "11-20 17:57:36.324  3968  3968 I adbd    : adbd service requested 'shell:logcat'",
            "11-20 17:57:36.400  1500  1600 I AdbDebuggingManager: Wireless debugging disabled",
            "11-20 17:57:36.500  1500  1600 D UsbDeviceManager: setAdbEnabled: false",
            // Who wrote the setting: the writer's pid is the evidence, and the text is the platform's own.
            "11-20 17:36:05.896 42896 17022 W Settings: Setting adb_enabled has moved from android.provider.Settings.Secure to android.provider.Settings.Global.",
            "11-20 17:36:05.900  1500  1600 I ActivityManager: Process com.baba.callvault (pid 38978) USB debugging lost",
        )
        assertEquals(keep, LogcatHealth.debuggingLines(keep, ours))
    }

    @Test fun `a pid of ours is not trusted long after its last own line`() {
        val lines = listOf(
            "11-20 17:00:00.000 38978 38978 I CV:AdbShell: connected",
            "11-20 19:00:00.000 38978 38978 I Chat    : usb debugging tips for developers",
        )
        assertEquals(listOf(lines[0]), LogcatHealth.debuggingLines(lines, ours))
    }
}
