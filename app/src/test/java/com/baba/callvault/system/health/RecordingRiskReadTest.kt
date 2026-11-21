package com.baba.callvault.system.health

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** R5: the Android reads behind the card; one that throws must read as "not holding", never crash. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordingRiskReadTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `a context whose services all throw gives an all-null reading`() {
        val broken = object : android.content.ContextWrapper(app) {
            override fun getSystemService(name: String): Any = throw IllegalStateException("boom")
            override fun getPackageManager(): android.content.pm.PackageManager = throw IllegalStateException("boom")
        }
        val reading = RecordingRiskReasons.readRaw(broken)
        assertEquals(RecordingRiskReasons.Reading(null, null, null, null), reading)
        assertEquals(emptyList<RiskReason>(), RecordingRiskReasons.fromReading(reading))
    }

    @Test
    fun `a normal phone reads without throwing and the app is not exempt by default`() {
        val reading = RecordingRiskReasons.readRaw(app as Context)
        assertEquals(true, reading.batteryLimited)
        assertNotNull(RecordingRiskReasons.read(app))
    }
}
