package com.baba.callvault.system.health

import com.baba.callvault.system.health.RecordingRiskReasons.Reading
import com.baba.callvault.system.health.RiskReason.APP_HIBERNATION
import com.baba.callvault.system.health.RiskReason.BACKGROUND_RESTRICTED
import com.baba.callvault.system.health.RiskReason.BATTERY_LIMITS
import com.baba.callvault.system.health.RiskReason.NOTIFICATIONS_OFF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R5: which things can stop a call being recorded, in the order the card names them. */
class RecordingRiskReasonsTest {

    private val allHold = Reading(batteryLimited = true, backgroundRestricted = true, hibernation = true, notificationsOff = true)

    @Test
    fun `nothing holding gives no reasons`() {
        assertEquals(emptyList<RiskReason>(), RecordingRiskReasons.fromReading(Reading(false, false, false, false)))
    }

    @Test
    fun `every reason alone gives just that reason`() {
        assertEquals(listOf(BATTERY_LIMITS), RecordingRiskReasons.fromReading(Reading(true, false, false, false)))
        assertEquals(listOf(BACKGROUND_RESTRICTED), RecordingRiskReasons.fromReading(Reading(false, true, false, false)))
        assertEquals(listOf(APP_HIBERNATION), RecordingRiskReasons.fromReading(Reading(false, false, true, false)))
        assertEquals(listOf(NOTIFICATIONS_OFF), RecordingRiskReasons.fromReading(Reading(false, false, false, true)))
    }

    @Test
    fun `all four combinations come out in priority order`() {
        assertEquals(
            listOf(BATTERY_LIMITS, BACKGROUND_RESTRICTED, APP_HIBERNATION, NOTIFICATIONS_OFF),
            RecordingRiskReasons.fromReading(allHold),
        )
    }

    @Test
    fun `all sixteen combinations keep the priority order and match the flags`() {
        for (mask in 0..15) {
            val r = Reading(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0, mask and 8 != 0)
            val expected = listOfNotNull(
                BATTERY_LIMITS.takeIf { r.batteryLimited == true },
                BACKGROUND_RESTRICTED.takeIf { r.backgroundRestricted == true },
                APP_HIBERNATION.takeIf { r.hibernation == true },
                NOTIFICATIONS_OFF.takeIf { r.notificationsOff == true },
            )
            assertEquals("mask $mask", expected, RecordingRiskReasons.fromReading(r))
        }
    }

    @Test
    fun `a read that failed is not a reason`() {
        val unreadable = Reading(null, null, null, null)
        assertEquals(emptyList<RiskReason>(), RecordingRiskReasons.fromReading(unreadable))
        assertEquals(listOf(APP_HIBERNATION), RecordingRiskReasons.fromReading(unreadable.copy(hibernation = true)))
    }

    @Test
    fun `the notice never lists notifications being off`() {
        val notice = RecordingRiskReasons.forNotice(RecordingRiskReasons.fromReading(allHold))
        assertEquals(listOf(BATTERY_LIMITS, BACKGROUND_RESTRICTED, APP_HIBERNATION), notice)
        assertEquals(emptyList<RiskReason>(), RecordingRiskReasons.forNotice(listOf(NOTIFICATIONS_OFF)))
    }

    @Test
    fun `the card shows risk when it would otherwise say ready`() {
        val reasons = listOf(BATTERY_LIMITS, APP_HIBERNATION)
        assertEquals(reasons, RecordingRiskReasons.forHero(statusIsReady = true, healthIsProblem = false, reasons = reasons))
    }

    @Test
    fun `risk never replaces a real error the card already shows`() {
        val reasons = listOf(BATTERY_LIMITS)
        assertTrue(RecordingRiskReasons.forHero(statusIsReady = false, healthIsProblem = false, reasons = reasons).isEmpty())
        assertTrue(RecordingRiskReasons.forHero(statusIsReady = true, healthIsProblem = true, reasons = reasons).isEmpty())
        assertTrue(RecordingRiskReasons.forHero(statusIsReady = false, healthIsProblem = true, reasons = reasons).isEmpty())
    }

    @Test
    fun `every reason has its own names for the card and the notice`() {
        assertEquals(RiskReason.values().size, RiskReason.values().map { it.fixTextRes }.toSet().size)
        assertEquals(RiskReason.values().size, RiskReason.values().map { it.lineTextRes }.toSet().size)
    }
}
