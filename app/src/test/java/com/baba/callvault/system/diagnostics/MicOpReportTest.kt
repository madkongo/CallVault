/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the app-op that actually drives the microphone indicator.
 *
 * This exists because `dumpsys audio`'s record-activity log cannot answer the question — see
 * [RecordActivityReport]. `OP_RECORD_AUDIO` is what SystemUI watches, and `dumpsys appops` marks a
 * still-running one with `Running start at:`. Both fixtures are real output shapes taken from
 * devices, including one where the op was started deliberately to capture the running form.
 */
class MicOpReportTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing fixture $name" }
            .bufferedReader().readText()

    @Test
    fun `a running RECORD_AUDIO op under the shell uid is found and named`() {
        // Act
        val held = MicOpReport.heldMicOps(fixture("dumpsys_appops_mic_running.txt"))

        // Assert — only the mic op, and only for shell; gearhead's RECORD_AUDIO_OUTPUT is not a mic op.
        assertEquals(1, held.size)
        assertEquals("RECORD_AUDIO", held[0].op)
        assertEquals("com.android.shell", held[0].pack)
    }

    @Test
    fun `a completed op with a closed duration is not reported as held`() {
        assertTrue(MicOpReport.heldMicOps(fixture("dumpsys_appops_mic_idle.txt")).isEmpty())
    }

    @Test
    fun `RECORD_AUDIO_OUTPUT is never treated as holding the microphone`() {
        // The submix op. It cannot light the indicator, so it must never appear here — that
        // conflation is the whole reason this class exists.
        val held = MicOpReport.heldMicOps(fixture("dumpsys_appops_mic_running.txt"))
        assertTrue(held.none { it.op == "RECORD_AUDIO_OUTPUT" })
    }

    @Test
    fun `the verdict says the microphone is held, and by whom`() {
        val rendered = MicOpReport.render(fixture("dumpsys_appops_mic_running.txt"))
        assertTrue(rendered.contains("HELD"))
        assertTrue(rendered.contains("com.android.shell"))
    }

    @Test
    fun `two shell-uid ops are not both called CallVault's`() {
        val dump = """
              Uid 2000:
                Package com.android.shell:
                  RECORD_AUDIO (allow):
                      Running start at: +53ms
                Package com.termux:
                  RECORD_AUDIO (allow):
                      Running start at: +9s
        """.trimIndent()
        val rendered = MicOpReport.render(dump)
        assertTrue(rendered.contains("2 of them as uid 2000"))
        assertTrue(rendered.contains("another shell/adb process"))
        assertFalse(rendered.contains("that is CallVault's privileged capture"))
        // Each line is labelled as held by the shell user, never as certainly ours.
        assertEquals(2, rendered.lines().count { it.trimStart().startsWith("uid=2000") && it.contains("another shell/adb process") })
        assertFalse(rendered.contains("<-- CallVault's privileged capture"))
    }

    @Test
    fun `the shell-user process list shows only shell processes`() {
        val ps = """
            USER           PID ARGS
            root             1 /init
            shell         4321 app_process / com.baba.callvault.server.RecorderServer
            u0_a186       5000 com.baba.callvault
            shell         6000 scrcpy-server
        """.trimIndent()
        val out = MicOpReport.shellProcesses(ps)
        assertTrue(out.contains("4321"))
        assertTrue(out.contains("scrcpy-server"))
        assertFalse(out.contains("/init"))
        assertFalse(out.contains("u0_a186"))
        assertTrue(MicOpReport.shellProcesses(null).contains("Could not read"))
    }

    @Test
    fun `the verdict is explicit when nothing holds the microphone`() {
        val rendered = MicOpReport.render(fixture("dumpsys_appops_mic_idle.txt"))
        assertFalse(rendered.contains("HELD"))
        assertTrue(rendered.contains("No microphone app-op is running"))
    }

    @Test
    fun `missing input is reported as unavailable rather than as a clean bill of health`() {
        assertTrue(MicOpReport.render(null).contains("unavailable"))
        assertTrue(MicOpReport.render("").contains("unavailable"))
    }

    @Test
    fun `the device-side filtered shape still parses, Access lines and all`() {
        // What SystemLogCollector actually feeds it: the on-device grep keeps uid, package, every op
        // header and the running markers, and drops `null=[`, `Access:` and `]`. The parser must not
        // depend on any of the dropped lines.
        val filtered = """
              Uid 2000:
                Package com.android.shell:
                  RECORD_AUDIO (allow): 
                      Running start at: +53ms
                  READ_CLIPBOARD (allow): 
        """.trimIndent()

        val held = MicOpReport.heldMicOps(filtered)

        assertEquals(1, held.size)
        assertEquals("RECORD_AUDIO", held[0].op)
        assertEquals("2000", held[0].uid)
    }

    @Test
    fun `a running non-mic op is never credited to the mic op above it`() {
        // The reason the device-side filter keeps EVERY op header rather than only the mic ones.
        // With CAMERA's header dropped, this Running line would be read as RECORD_AUDIO still held.
        val filtered = """
              Uid 2000:
                Package com.android.shell:
                  RECORD_AUDIO (allow): 
                  CAMERA (allow): 
                      Running start at: +5s
        """.trimIndent()

        assertTrue(MicOpReport.heldMicOps(filtered).isEmpty())
    }
}
