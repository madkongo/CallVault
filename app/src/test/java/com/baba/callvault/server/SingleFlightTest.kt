package com.baba.callvault.server

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** BabApps #95: however many paths ask for the recorder at once, the bring-up runs once. */
class SingleFlightTest {

    @Test
    fun concurrent_callers_run_the_work_once_and_share_its_result() {
        val flight = SingleFlight<Boolean>()
        val runs = AtomicInteger()
        val joined = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            // The first caller holds the run open until every other caller has arrived.
            val first = pool.submit<Boolean> {
                flight.run { runs.incrementAndGet(); started.countDown(); release.await(5, TimeUnit.SECONDS); true }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val others = (1..6).map { pool.submit<Boolean> { flight.run(onJoin = { joined.incrementAndGet() }) { runs.incrementAndGet(); false } } }
            // Wait until all six are parked as joiners, then let the first finish.
            val deadline = System.currentTimeMillis() + 5_000
            while (joined.get() < 6 && System.currentTimeMillis() < deadline) Thread.sleep(5)
            assertEquals(6, joined.get())
            release.countDown()
            assertTrue(first.get(5, TimeUnit.SECONDS))
            others.forEach { assertTrue("a joiner takes the first run's result", it.get(5, TimeUnit.SECONDS)) }
            assertEquals("the work ran exactly once", 1, runs.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun a_caller_after_the_run_has_finished_starts_a_new_one() {
        val flight = SingleFlight<Int>()
        val runs = AtomicInteger()
        assertEquals(1, flight.run { runs.incrementAndGet() })
        assertEquals(2, flight.run { runs.incrementAndGet() })
    }

    @Test
    fun a_failure_reaches_the_joiners_and_the_next_caller_can_try_again() {
        val flight = SingleFlight<Int>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<Result<Int>> {
                runCatching { flight.run { started.countDown(); release.await(5, TimeUnit.SECONDS); error("launch failed") } }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val joinedFlag = AtomicInteger()
            val second = pool.submit<Result<Int>> { runCatching { flight.run(onJoin = { joinedFlag.incrementAndGet() }) { 7 } } }
            val deadline = System.currentTimeMillis() + 5_000
            while (joinedFlag.get() < 1 && System.currentTimeMillis() < deadline) Thread.sleep(5)
            release.countDown()
            assertTrue(first.get(5, TimeUnit.SECONDS).isFailure)
            assertTrue(second.get(5, TimeUnit.SECONDS).isFailure)
            assertEquals(7, flight.run { 7 })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a joiner with a deadline gives up on a wedged leader and the leader is not disturbed`() {
        val flight = SingleFlight<Int>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val leader = pool.submit<Int> { flight.run { started.countDown(); release.await(10, TimeUnit.SECONDS); 5 } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val begun = System.nanoTime()
            val joiner = pool.submit<Result<Int>> { runCatching { flight.run(joinTimeoutMs = 300) { 9 } } }
            val outcome = joiner.get(5, TimeUnit.SECONDS)
            val tookMs = (System.nanoTime() - begun) / 1_000_000
            assertTrue("gave up at the deadline, took $tookMs ms", tookMs < 3_000)
            assertTrue(outcome.exceptionOrNull() is java.util.concurrent.TimeoutException)
            release.countDown()
            assertEquals(5, leader.get(5, TimeUnit.SECONDS))
            assertEquals("the next caller starts a new run", 7, flight.run { 7 })
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }
}
