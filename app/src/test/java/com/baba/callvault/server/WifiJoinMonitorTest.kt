/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review N15, recorder side: Wi-Fi joins are watched by the recorder itself, with a callback that stays registered
 * (a PendingIntent request is dropped by Android after its first delivery, which is why the OP9 saw only one join on
 * 2026-11-19). Every outcome becomes an event for the app's always-on log.
 */
class WifiJoinMonitorTest {

    /** A watch that records registrations; [deliver] plays Android calling onAvailable. */
    private class FakeWatch : WifiNetworkWatch {
        var starts = 0
        var stops = 0
        var failWith: RuntimeException? = null
        var listener: ((String) -> Unit)? = null
        override fun start(onAvailable: (String) -> Unit) {
            failWith?.let { throw it }
            starts++
            listener = onAvailable
        }
        override fun stop() {
            stops++
            listener = null
        }
        fun deliver(network: String) = listener?.invoke(network)
    }

    /** Holds the one pending check, as a Handler with a replaced message would. */
    private class FakeRunner : DelayedRunner {
        var pending: (() -> Unit)? = null
        val delays = mutableListOf<Long>()
        override fun runAfter(delayMs: Long, task: () -> Unit) {
            delays += delayMs
            pending = task
        }
        override fun cancelPending() {
            pending = null
        }
        fun runPending() = pending?.also { pending = null }?.invoke()
    }

    private val watch = FakeWatch()
    private val runner = FakeRunner()
    private val events = mutableListOf<WifiJoinEvent>()
    private val asked = mutableListOf<Set<String>>()
    private var answer: () -> KeptSwitchResult = {
        KeptSwitchResult(KeptSwitchOutcome.APPROVED_RESTORED_OFF, "k-new", "**:**:**:**:88:01", 270L)
    }

    /** What the app answers before each approval; null = it could not be reached. */
    private var appSays: ApprovalConfig? = ApprovalConfig(wanted = true, knownKeys = emptySet(), salt = "salt")
    private var confirms = 0
    private var confirmThrows: Throwable? = null
    private var elapsedNow = 0L

    private fun monitor(w: FakeWatch = watch, r: FakeRunner = runner) = WifiJoinMonitor(
        watch = w,
        runner = r,
        approve = { keys, _ -> asked += keys; answer() },
        emit = { events += it },
        confirm = { confirms++; confirmThrows?.let { throw it }; appSays },
        clock = { 1_000L },
        elapsed = { elapsedNow },
    )

    private fun texts() = events.map { it.text }

    @Test
    fun `arming registers the watch and says so`() {
        val m = monitor()

        assertTrue(m.arm(true, setOf("k0"), "salt"))

        assertEquals(1, watch.starts)
        assertTrue(texts().single().startsWith("watching Wi-Fi joins in the recorder"))
    }

    @Test
    fun `arming again never stacks a second registration`() {
        val m = monitor()
        m.arm(true, emptySet(), "salt")
        m.arm(true, emptySet(), "salt")
        m.arm(true, setOf("k9"), "salt")

        assertEquals(1, watch.starts)
        assertEquals(0, watch.stops)
    }

    @Test
    fun `a second join after the first is still checked`() {
        val m = monitor()
        m.arm(true, emptySet(), "salt")

        watch.deliver("100")
        runner.runPending()
        elapsedNow += 60_000L
        watch.deliver("101")
        runner.runPending()

        assertEquals(2, asked.size)
        assertEquals(listOf(WifiJoinMonitor.JOIN_SETTLE_MS, WifiJoinMonitor.JOIN_SETTLE_MS), runner.delays)
        assertEquals(2, texts().count { it.startsWith("Wi-Fi network available") })
        assertEquals(2, texts().count { it.startsWith("approved access point") })
    }

    @Test
    fun `joins in quick succession are checked once, after the last`() {
        val m = monitor()
        m.arm(true, emptySet(), "salt")

        watch.deliver("100")
        watch.deliver("101")
        runner.runPending()

        assertEquals(1, asked.size)
    }

    @Test
    fun `an approved access point carries its key to the app and is passed as known next time`() {
        val m = monitor()
        m.arm(true, setOf("k0"), "salt")

        watch.deliver("100")
        runner.runPending()
        elapsedNow += 60_000L
        watch.deliver("101")
        runner.runPending()

        assertEquals("k-new", events.first { it.text.startsWith("approved") }.trustedKey)
        assertEquals(listOf(setOf("k0"), setOf("k0", "k-new")), asked)
    }

    @Test
    fun `an access point Android already trusts is reported as skipped, with its key`() {
        answer = { KeptSwitchResult(KeptSwitchOutcome.ALREADY_TRUSTED_BY_ANDROID, "k-40", "**:**:**:**:40:18") }
        val m = monitor()
        m.arm(true, emptySet(), "salt")

        watch.deliver("100")
        runner.runPending()

        val last = events.last()
        assertEquals("skipped: **:**:**:**:40:18 is already on Android's trusted list for Wireless debugging", last.text)
        assertEquals("k-40", last.trustedKey)
        assertFalse(last.warn)
    }

    @Test
    fun `a check that throws is logged as a failure with its reason`() {
        answer = { throw IllegalStateException("no adb service") }
        val m = monitor()
        m.arm(true, emptySet(), "salt")

        watch.deliver("100")
        runner.runPending()

        val last = events.last()
        assertTrue(last.warn)
        assertEquals("failed: the check threw IllegalStateException: no adb service", last.text)
    }

    @Test
    fun `disarming unregisters, drops a pending check, and a later join is not checked`() {
        val m = monitor()
        m.arm(true, emptySet(), "salt")
        watch.deliver("100")

        assertFalse(m.arm(false, emptySet(), "salt"))
        runner.runPending()
        watch.deliver("101")

        assertEquals(1, watch.stops)
        assertTrue(asked.isEmpty())
        assertEquals("stopped watching Wi-Fi joins in the recorder", texts().last())
    }

    @Test
    fun `disarming when not watching does nothing and logs nothing`() {
        val m = monitor()

        assertFalse(m.arm(false, emptySet(), "salt"))

        assertEquals(0, watch.stops)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a recorder started again is armed again, and checks the network it is on`() {
        monitor().arm(true, emptySet(), "salt")

        // The recorder process died and Shizuku started a new one: a fresh monitor, a fresh registration.
        val freshWatch = FakeWatch()
        val freshRunner = FakeRunner()
        val fresh = monitor(freshWatch, freshRunner)
        assertTrue(fresh.arm(true, emptySet(), "salt"))
        freshWatch.deliver("100") // Android reports the current network to a new callback at once
        freshRunner.runPending()

        assertEquals(1, freshWatch.starts)
        assertEquals(1, asked.size)
    }

    @Test
    fun `a registration that fails is logged, reported as not watching, and tried again on the next arm`() {
        watch.failWith = SecurityException("no access")
        val m = monitor()

        assertFalse(m.arm(true, emptySet(), "salt"))
        val failure = events.single()
        assertTrue(failure.warn)
        assertEquals("could not watch Wi-Fi joins in the recorder: SecurityException: no access", failure.text)

        watch.failWith = null
        assertTrue(m.arm(true, emptySet(), "salt"))
        assertEquals(1, watch.starts)
    }

    @Test
    fun `events cross the binder intact, text with separators included`() {
        val event = WifiJoinEvent(atMs = 1_763_565_175_000L, warn = true, text = "failed: a|b|c", trustedKey = "k1")

        assertEquals(event, WifiJoinEvent.decode(event.encode()))
        assertEquals(WifiJoinEvent(5L, false, "x", ""), WifiJoinEvent.decode(WifiJoinEvent(5L, false, "x").encode()))
        assertNull(WifiJoinEvent.decode("garbage"))
        assertNull(WifiJoinEvent.decode(null))
    }

    @Test
    fun `a check that fails in a class initializer names the real exception`() {
        answer = { throw ExceptionInInitializerError(java.util.regex.PatternSyntaxException("Syntax error", "(?:}|x)", 3)) }
        val m = monitor()
        m.arm(true, emptySet(), "salt")

        watch.deliver("100")
        runner.runPending()

        val last = events.last()
        assertTrue(last.warn)
        assertTrue(last.text, last.text.startsWith("failed: the check threw ExceptionInInitializerError, caused by PatternSyntaxException: Syntax error"))
    }

    @Test
    fun `asking the app that throws is logged with the reason, and nothing is approved`() {
        val m = monitor()
        m.arm(true, emptySet(), "salt")
        confirmThrows = IllegalStateException("binder died")

        watch.deliver("100")
        runner.runPending()

        assertTrue(asked.isEmpty())
        val last = events.last()
        assertTrue(last.warn)
        assertTrue(last.text, last.text.startsWith("skipped: could not confirm with the app"))
        assertTrue(last.text, last.text.contains("IllegalStateException: binder died"))
    }

    @Test
    fun `nothing is approved when the app cannot confirm the setting is still on`() {
        val m = monitor()
        m.arm(true, emptySet(), "salt")
        appSays = null

        watch.deliver("100")
        runner.runPending()

        assertTrue(asked.isEmpty())
        assertEquals(1, confirms)
        val last = events.last()
        assertTrue(last.warn)
        assertTrue(last.text, last.text.startsWith("skipped: could not confirm with the app"))
    }

    @Test
    fun `a setting switched off while the recorder was not told - nothing approved, and the watch stops itself`() {
        val m = monitor()
        m.arm(true, emptySet(), "salt")
        appSays = ApprovalConfig(wanted = false, knownKeys = emptySet(), salt = "")

        watch.deliver("100")
        runner.runPending()
        watch.deliver("101")

        assertTrue(asked.isEmpty())
        assertEquals(1, watch.stops)
        assertTrue(texts().last().startsWith("skipped: the app says approving is no longer wanted"))
        assertEquals(null, runner.pending)
    }

    @Test
    fun `the app's keys at the check are added to those it armed with`() {
        val m = monitor()
        m.arm(true, setOf("k0"), "salt")
        m.arm(true, setOf("k1"), "salt")
        appSays = ApprovalConfig(wanted = true, knownKeys = setOf("k2"), salt = "salt")

        watch.deliver("100")
        runner.runPending()

        assertEquals(listOf(setOf("k0", "k1", "k2")), asked)
    }

    @Test
    fun `a flapping access point is checked at most every 30 s and logs one line per 30 s`() {
        val m = monitor()
        m.arm(true, emptySet(), "salt")
        events.clear()

        watch.deliver("100")
        runner.runPending() // first check at 0 s
        repeat(9) { elapsedNow += 1_000L; watch.deliver("${101 + it}") }

        assertEquals(1, asked.size)
        assertEquals(1, texts().count { it.startsWith("Wi-Fi network available") })
        assertEquals(30_000L - 9_000L, runner.delays.last()) // the next check waits for the 30 s spacing
        runner.runPending()
        assertEquals(2, asked.size)

        elapsedNow = 40_000L
        watch.deliver("200")
        val line = texts().last { it.startsWith("Wi-Fi network available") }
        assertTrue(line, line.endsWith("(9 more Wi-Fi joins since the last line)"))
    }
}
