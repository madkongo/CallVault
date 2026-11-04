package com.baba.callvault.integrations.adb

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbDebuggingRestorePolicyTest {

    private val now = 1_000_000_000L

    private fun decide(
        needsAdb: Boolean = true,
        hasGrant: Boolean = true,
        screenOn: Boolean = false,
        locked: Boolean = true,
        recent: List<Long> = emptyList(),
    ) = UsbDebuggingRestorePolicy.decide(needsAdb, hasGrant, screenOn, locked, recent, now)

    @Test fun `screen off restores by itself`() =
        assertEquals(UsbRestore.RESTORE, decide(screenOn = false, locked = false))

    @Test fun `screen on but locked restores by itself`() =
        assertEquals(UsbRestore.RESTORE, decide(screenOn = true, locked = true))

    @Test fun `screen on and unlocked may be the user so asks`() =
        assertEquals(UsbRestore.ASK, decide(screenOn = true, locked = false))

    @Test fun `not using our own adb leaves it alone`() =
        assertEquals(UsbRestore.NONE, decide(needsAdb = false))

    @Test fun `without the grant leaves it alone`() =
        assertEquals(UsbRestore.NONE, decide(hasGrant = false))

    @Test fun `three restores in ten minutes stops and asks`() {
        val recent = listOf(now - 60_000, now - 120_000, now - 180_000)
        assertEquals(UsbRestore.ASK, decide(recent = recent))
    }

    @Test fun `old restores do not count towards the loop guard`() {
        val old = now - UsbDebuggingRestorePolicy.WINDOW_MS - 1
        assertEquals(UsbRestore.RESTORE, decide(recent = listOf(old, old, old, now - 1_000)))
    }

    @Test fun `two recent restores still restores`() =
        assertEquals(UsbRestore.RESTORE, decide(recent = listOf(now - 1_000, now - 2_000)))
}
