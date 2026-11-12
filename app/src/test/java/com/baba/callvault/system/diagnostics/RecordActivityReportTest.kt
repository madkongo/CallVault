/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading Android's own record of who holds a microphone.
 *
 * The healthy fixture is a real `dumpsys audio` taken on 2026-09-01 from a device that does NOT show
 * the stuck-microphone symptom — a control, so "reports nothing" is known to mean nothing is wrong
 * rather than the parser having failed to match.
 */
class RecordActivityReportTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing fixture $name" }
            .bufferedReader().readText()

    @Test
    fun `a device with no symptom has nothing left open`() {
        // Act
        val open = RecordActivityReport.openCaptures(fixture("dumpsys_audio_healthy.txt"))

        // Assert — every rec start on that phone had a matching rec stop.
        assertEquals(emptyList<RecordActivityReport.OpenCapture>(), open)
    }

    @Test
    fun `the parser really does match that fixture, so the empty result means something`() {
        // A parser that matched nothing would also return "nothing open". Prove it matched.
        val text = fixture("dumpsys_audio_healthy.txt")
        assertTrue("fixture should contain rec events", text.contains("rec start riid:"))
        // Stops are what emptied it, so removing them must produce open captures.
        val withoutStops = text.lineSequence().filterNot { " rec stop " in it }.joinToString("\n")
        assertTrue(RecordActivityReport.openCaptures(withoutStops).isNotEmpty())
    }

    @Test
    fun `a hotword listener update with no stop is not another app holding the mic`() {
        // BabApps item 94: the phone's own HOTWORD listener only ever logs updates, never a stop.
        val dump = "11-11 10:00:00:000 rec update riid:7 uid:10200 session:7 src:HOTWORD not silenced pack:com.google.android.googlequicksearchbox"

        assertEquals("it must not be listed as a leak", emptyList<RecordActivityReport.OpenCapture>(), RecordActivityReport.openCaptures(dump))
        assertEquals("it is listed as unpairable instead", 1, RecordActivityReport.unpairableEntries(dump).size)
        listOf("HOTWORD", "RADIO_TUNER", "ULTRASOUND", "ECHO_REFERENCE").forEach {
            assertTrue(it, !RecordActivityReport.drivesMicIndicator(it))
        }
        assertTrue(RecordActivityReport.drivesMicIndicator("MIC"))
    }

    @Test
    fun `a capture with no stop is reported, and named as ours when it is shell`() {
        // Arrange — one shell capture never stopped, one app capture opened and closed.
        val dump = """
            08-31 20:13:49:297 rec start riid:1 uid:10394 session:1 src:MIC not silenced pack:com.whatsapp
            08-31 20:13:55:296 rec stop riid:1 uid:10394 session:1 src:MIC not silenced pack:com.whatsapp
            09-01 10:18:56:322 rec start riid:2 uid:2000 session:2 src:MIC not silenced pack:com.android.shell
        """.trimIndent()

        // Act
        val open = RecordActivityReport.openCaptures(dump)

        // Assert
        assertEquals(1, open.size)
        assertEquals("2", open.single().riid)
        assertEquals("MIC", open.single().source)
        assertTrue(open.single().isShell)
        // Named as ours: the rendered section must attribute the shell capture to CallVault.
        val rendered = RecordActivityReport.render(dump)
        assertTrue(rendered.contains("STILL OPEN"))
        assertTrue(rendered.contains("CallVault's privileged capture"))
    }

    @Test
    fun `a recording that began before the log window still counts as open`() {
        // Arrange — the events log is a ring, so a long call shows only its later 'update'.
        val dump = "09-01 09:42:17:035 rec update riid:9 uid:2000 session:9 src:VOICE_CALL not silenced pack:com.android.shell"

        // Act + Assert — treating an unpaired update as closed would hide the longest-lived capture.
        assertEquals(1, RecordActivityReport.openCaptures(dump).size)
    }

    @Test
    fun `a stuck capture belonging to another app is not blamed on CallVault`() {
        // Arrange
        val dump = "09-01 10:18:56:322 rec start riid:3 uid:10394 session:3 src:MIC not silenced pack:com.whatsapp"

        // Act
        val rendered = RecordActivityReport.render(dump)

        // Assert
        assertTrue(rendered, "Another app is holding the microphone" in rendered)
    }

    @Test
    fun `an unreadable dump says so instead of claiming everything is fine`() {
        assertTrue(RecordActivityReport.render(null).contains("could not read"))
        assertTrue(RecordActivityReport.render("").contains("could not read"))
    }

    // ---- Regression: the submix false positive that cost a day of investigation ----------------

    @Test
    fun `system-only sources are never counted as captures left open`() {
        // Arrange — real dumpsys from the maintainer's OP12 (OxygenOS 16), a phone with NO
        // stuck-microphone symptom. It contains REMOTE_SUBMIX entries that Android structurally
        // cannot pair with a stop: isSystemOnlyAudioSource() makes RecordingActivityMonitor log the
        // event and return before updateSnapshot(), so no rec stop is ever emitted for them.
        val dump = fixture("dumpsys_audio_submix_orphans.txt")

        // Act
        val open = RecordActivityReport.openCaptures(dump)

        // Assert — the old parser reported five "still open" captures here. All of them were submix.
        assertTrue("REMOTE_SUBMIX must never be reported as open", open.none { it.source == "REMOTE_SUBMIX" })
    }

    @Test
    fun `a healthy phone with submix orphans is not accused of holding the microphone`() {
        val rendered = RecordActivityReport.render(fixture("dumpsys_audio_submix_orphans.txt"))

        // The claim that sent the investigation the wrong way. It must not appear for submix-only
        // orphans, because REMOTE_SUBMIX notes RECORD_AUDIO_OUTPUT, which SystemUI does not watch.
        assertTrue(
            "must not claim the mic indicator for submix orphans",
            !rendered.contains("puts the green microphone dot"),
        )
    }

    @Test
    fun `the unpairable entries are still shown, but in their own bucket`() {
        // They are not evidence of a leak, but hiding them entirely would make the report look like
        // it had failed to parse. They get counted and labelled instead.
        val rendered = RecordActivityReport.render(fixture("dumpsys_audio_submix_orphans.txt"))
        assertTrue(rendered.contains("REMOTE_SUBMIX"))
        assertTrue(rendered.lowercase().contains("cannot be paired") || rendered.lowercase().contains("not a leak"))
    }

    @Test
    fun `the event kind is kept, because it is the discriminating field`() {
        val dump = """
            01-01 00:00:00:000 rec update riid:11 uid:2000 session:1 src:MIC not silenced pack:com.android.shell
        """.trimIndent()

        val open = RecordActivityReport.openCaptures(dump)

        assertEquals(1, open.size)
        assertEquals("update", open[0].kind)
        assertTrue(open[0].toString().contains("update"))
    }

    @Test
    fun `a saturated ring is called out, because evidence may have been evicted`() {
        // The log is a 50-entry EventLogger. Un-stoppable submix entries accumulate in it, so a real
        // capture's rec start can be pushed out entirely — "nothing open" then means "we cannot see".
        val rendered = RecordActivityReport.render(fixture("dumpsys_audio_submix_orphans.txt"))
        assertTrue(rendered.lowercase().contains("ring"))
    }

    @Test
    fun `a genuine MIC capture left open is still reported and still blamed`() {
        // The whole point of the class must survive the fix.
        val dump = """
            01-01 00:00:00:000 rec start riid:7 uid:2000 session:3 src:MIC not silenced pack:com.android.shell
        """.trimIndent()

        val open = RecordActivityReport.openCaptures(dump)
        assertEquals(1, open.size)
        assertEquals("MIC", open[0].source)
        assertTrue(RecordActivityReport.render(dump).contains("STILL OPEN"))
    }
}
