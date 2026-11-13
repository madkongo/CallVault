package com.baba.callvault.system.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The always-on #95 lines say what they were meant to say. */
class MicOpDiagnosticsTest {

    private val appops = """
          Uid 2000:
            Package com.android.shell:
              RECORD_AUDIO (allow):
                  Running start at: +1m41s110ms
          Uid 10001:
            Package com.other:
              RECORD_AUDIO (allow):
                  Running start at: +5s
    """.trimIndent()

    private val ps = """
        USER           PID  PPID STIME ARGS
        root             1     0 11:00 /init
        shell         8852  8850 19:43 sh -c setsid sh -c 'CLASSPATH=/a exec app_process / com.baba.callvault.server.RecorderServer /a' & sleep 3
        shell         8854     1 19:43 app_process / com.baba.callvault.server.RecorderServer /a
        u0_a310       9000     1 19:00 com.other
    """.trimIndent()

    @Test
    fun shell_ops_are_reported_with_when_they_started_and_other_uids_are_not() {
        assertEquals("RECORD_AUDIO running since +1m41s110ms", MicOpDiagnostics.shellOpsSummary(appops))
        assertEquals("none running", MicOpDiagnostics.shellOpsSummary("  Uid 2000:\n    Package com.android.shell:\n      CAMERA (allow): \n"))
        assertEquals("unreadable", MicOpDiagnostics.shellOpsSummary(null))
    }

    @Test
    fun the_process_list_keeps_shell_processes_with_parent_and_start_time() {
        val out = MicOpDiagnostics.shellProcessLines(ps)
        assertTrue(out, "shell 8852 8850 19:43" in out)
        assertTrue(out, "shell 8854 1 19:43" in out)
        assertFalse(out, "/init" in out)
        assertFalse(out, "com.other" in out)
    }

    @Test
    fun the_bring_up_line_keeps_only_recorder_looking_processes() {
        val out = MicOpDiagnostics.recorderProcessLines(ps)
        assertTrue(out, "8852" in out && "8854" in out)
        assertFalse(out, "/init" in out)
        assertEquals("process list unreadable", MicOpDiagnostics.recorderProcessLines(null))
    }

    @Test
    fun a_snapshot_line_has_the_label_the_mode_the_op_and_the_processes() {
        val line = MicOpDiagnostics.format("track invalidated", 3, appops, ps)
        assertTrue(line, line.startsWith("mic diag [track invalidated]: audioMode=3 "))
        assertTrue(line, "shellMicOps=RECORD_AUDIO running since +1m41s110ms" in line)
        assertTrue(line, "shellProcesses=" in line)
    }
}
