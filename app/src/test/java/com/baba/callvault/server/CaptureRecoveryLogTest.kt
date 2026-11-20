/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/**
 * Review N21b: which path built an app call's sink and mic — the normal constructor, the constructor-free rebuild
 * (which field layout), or a shell Application and a retry — was known only to the recorder's debug log, and the
 * vivo reporter has debug logging off. The recorder keeps a short summary of the non-default steps of the current
 * capture and hands it over with `captureDiagnostics()`; a phone where every record was built normally gets nothing.
 */
class CaptureRecoveryLogTest {

    private fun vivoCrash() = InvocationTargetException(
        NullPointerException("Attempt to invoke virtual method 'java.lang.String android.content.Context.getOpPackageName()' on a null object reference")
            .apply {
                stackTrace = arrayOf(
                    StackTraceElement("android.media.VivoAudioRecordImpl", "isSupportSubMixRecording", "X.java", 1),
                    StackTraceElement("android.media.AudioRecord", "<init>", "X.java", 1),
                    StackTraceElement("android.media.audiopolicy.AudioPolicy", "createAudioRecordSink", "X.java", 1),
                )
            },
    )

    private fun honorStartCrash() = NullPointerException().apply {
        stackTrace = arrayOf(
            StackTraceElement("java.util.Objects", "requireNonNull", "X.java", 1),
            StackTraceElement("android.media.AudioRecord", "startRecording", "X.java", 1),
            StackTraceElement("com.baba.callvault.server.VoipCaptureSession", "startInternal", "X.java", 1),
        )
    }

    private fun log(shellApp: String = "no") = CaptureRecoveryLog(shellAppState = { shellApp })

    private fun <T : Any> build(
        log: CaptureRecoveryLog, what: String, build: () -> T?, bypass: () -> T, repair: () -> Boolean = { true },
    ): T? = HostAudioRecord.buildWithRecovery(what, build, bypass, repair, recovery = log, layout = { "A17 layout" })

    private class Session(private val diagnostics: String) : RecordingSession {
        override fun start() = Unit
        override fun stop() = Unit
        override fun captureDiagnostics() = diagnostics
    }

    @Test
    fun a_call_built_the_normal_way_leaves_nothing_and_the_health_line_is_unchanged() {
        val log = log()
        val slot = CaptureDiagnosticsSlot(log)
        slot.startAccepted()
        build(log, "VoIP far-party sink", build = { "sink" }, bypass = { fail("no bypass"); "x" })
        build(log, "VoIP mic", build = { "mic" }, bypass = { fail("no bypass"); "x" })
        HostAudioRecord.startWithRepair("VoIP far", start = {}, repair = { fail("no repair"); true }, recovery = log)
        slot.sessionEnded(Session("near late by 40 ms"))
        assertEquals("near late by 40 ms", slot.value)
        assertNull(CaptureRecoveryLog.callEndLine(slot.value))
    }

    @Test
    fun a_rebuilt_sink_is_summarised_with_its_layout_and_the_normal_mic_after_it() {
        val log = log()
        build(log, "VoIP far-party sink", build = { throw vivoCrash() }, bypass = { "rebuilt" })
        build(log, "VoIP mic", build = { "mic" }, bypass = { "x" })
        val summary = log.drain()
        assertTrue(summary, summary.startsWith("sink=ctor crashed(NullPointerException: Attempt to invoke"))
        assertTrue(summary, summary.contains("> rebuilt(A17 layout)"))
        assertTrue(summary, summary.contains("; mic=normal"))
        assertTrue(summary, summary.endsWith("; shellApp=no"))
        assertEquals("", log.drain())
    }

    @Test
    fun a_shell_application_retry_is_summarised() {
        val log = log(shellApp = "installed(process uid)")
        var builds = 0
        build(
            log, "VoIP far-party sink",
            build = { builds++; if (builds == 1) throw vivoCrash() else "retried" },
            bypass = { throw NoSuchFieldException("No field mChannelCount in class Landroid/media/AudioRecord;") },
        )
        val summary = log.drain()
        assertTrue(summary, summary.contains("> rebuild failed(NoSuchFieldException: No field mChannelCount"))
        assertTrue(summary, summary.contains("> shellApp installed > retry ok"))
        assertTrue(summary, summary.endsWith("; shellApp=installed(process uid)"))
    }

    @Test
    fun failures_carry_their_reasons() {
        val log = log()
        build<String>(
            log, "VoIP far-party sink",
            build = { throw vivoCrash() },
            bypass = { throw NoSuchFieldException("mChannelCount") },
        )
        build<String>(
            log, "VoIP mic",
            build = { throw vivoCrash() },
            bypass = { throw NoSuchFieldException("mChannelCount") },
            repair = { false },
        )
        build<String>(log, "VoIP mic re-take", build = { throw SecurityException("EX_SECURITY") }, bypass = { "x" })
        val summary = log.drain()
        assertTrue(summary, summary.contains("sink=ctor crashed("))
        assertTrue(summary, summary.contains("> retry failed(NullPointerException: Attempt to invoke"))
        assertTrue(summary, summary.contains("mic=ctor crashed("))
        assertTrue(summary, summary.contains("> shellApp install failed"))
        assertTrue(summary, summary.contains("mic re-take=ctor failed(SecurityException: EX_SECURITY)"))
    }

    @Test
    fun a_refused_shell_application_says_why() {
        val log = log()
        build<String>(
            log, "VoIP mic",
            build = { throw vivoCrash() },
            bypass = { throw IllegalStateException("native_setup returned -38") },
        )
        val summary = log.drain()
        assertTrue(summary, summary.contains("> rebuild failed(IllegalStateException: native_setup returned -38)"))
        assertTrue(summary, summary.contains("> shellApp refused("))
    }

    @Test
    fun the_honor_start_repair_is_summarised() {
        val log = log(shellApp = "installed(shell uid)")
        var starts = 0
        HostAudioRecord.startWithRepair(
            "VoIP near",
            start = { starts++; if (starts == 1) throw honorStartCrash() },
            repair = { true },
            recovery = log,
        )
        val summary = log.drain()
        assertTrue(summary, summary.startsWith("mic start=crashed(NullPointerException) > shellApp installed > started"))
    }

    @Test
    fun a_start_repair_that_fails_is_summarised_before_the_error_propagates() {
        val log = log()
        val crash = honorStartCrash()
        try {
            HostAudioRecord.startWithRepair("VoIP far", start = { throw crash }, repair = { false }, recovery = log)
            fail("expected the crash")
        } catch (e: NullPointerException) {
            assertEquals(crash, e)
        }
        assertTrue(log.drain().startsWith("sink start=crashed(NullPointerException) > shellApp install failed"))
    }

    @Test
    fun the_summary_is_bounded() {
        val log = log()
        repeat(50) {
            build<String>(
                log, "VoIP mic re-take",
                build = { throw vivoCrash() },
                bypass = { throw NoSuchFieldException("x".repeat(5_000)) },
            )
        }
        val summary = log.drain()
        assertTrue("length ${summary.length}", summary.length <= CaptureRecoveryLog.MAX_SUMMARY)
        assertTrue(summary, summary.contains("more)"))
        assertTrue(summary, !summary.contains('\n'))
    }

    @Test
    fun a_start_failure_carries_the_summary_and_its_reason_still_reads_back() {
        val log = log()
        val slot = CaptureDiagnosticsSlot(log)
        slot.startAccepted()
        build<String>(log, "VoIP far-party sink", build = { throw vivoCrash() }, bypass = { throw NoSuchFieldException("mChannelCount") }, repair = { false })
        slot.startFailed(IllegalStateException("VoIP far-party sink unavailable (policy not armed?)"))
        val (health, recovery) = CaptureRecoveryLog.split(slot.value)
        assertEquals(
            "IllegalStateException: VoIP far-party sink unavailable (policy not armed?)",
            VoipStartFailure.reasonOf(health),
        )
        assertTrue(recovery!!, recovery.startsWith("sink=ctor crashed("))
        val line = CaptureRecoveryLog.callEndLine(slot.value)!!
        assertTrue(line, line.startsWith("app call capture recovery: sink=ctor crashed("))
    }

    @Test
    fun a_recorded_call_carries_the_summary_after_its_health() {
        val log = log()
        val slot = CaptureDiagnosticsSlot(log)
        slot.startAccepted()
        build(log, "VoIP far-party sink", build = { throw vivoCrash() }, bypass = { "rebuilt" })
        slot.sessionEnded(Session("near late by 40 ms"))
        val (health, recovery) = CaptureRecoveryLog.split(slot.value)
        assertEquals("near late by 40 ms", health)
        assertTrue(recovery!!, recovery.contains("rebuilt(A17 layout)"))
    }

    @Test
    fun an_accepted_start_forgets_what_happened_before_it() {
        val log = log()
        val slot = CaptureDiagnosticsSlot(log)
        build(log, "VoIP mic", build = { throw vivoCrash() }, bypass = { "rebuilt" })
        slot.startAccepted()
        slot.sessionEnded(Session(""))
        assertEquals("", slot.value)
    }

    @Test
    fun an_older_recorder_or_no_recovery_gives_no_line() {
        assertNull(CaptureRecoveryLog.callEndLine(null))
        assertNull(CaptureRecoveryLog.callEndLine(""))
        assertNull(CaptureRecoveryLog.callEndLine("near late by 40 ms"))
        assertEquals("near late by 40 ms" to null, CaptureRecoveryLog.split("near late by 40 ms"))
        assertEquals(null to null, CaptureRecoveryLog.split(null))
    }

    /** The app writes the line once per app call, before the recorded / not-started branches split. */
    @Test
    fun the_app_writes_the_line_for_both_a_recorded_and_a_failed_call() {
        val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
        val source = java.io.File(root, "com/baba/callvault/services/recording/VoipRecordingCoordinator.kt").readText()
        val written = source.indexOf("CaptureRecoveryLog.callEndLine(")
        assertTrue(written > 0)
        assertTrue(written < source.indexOf("if (!wroteAnything)"))
        assertTrue(written < source.indexOf("VoipAnswerHold.endDiscardReason("))
        assertTrue(source.contains("VoipStartFailure.reasonOf(captureHealth)"))
        assertTrue(source.contains("CaptureRecoveryLog.split("))
    }
}
