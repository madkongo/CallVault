package com.baba.callvault.transcription.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** R21: only a format refusal sends WAV, and it is remembered per service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContainerRefusalsTest {

    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("test_refusals", Context.MODE_PRIVATE)
    private val custom = CloudEndpoint(TranscriptionService.CUSTOM, "Custom", "http://10.0.0.5:8000/v1", "whisper-1")

    @Test
    fun `only 400 and 415 count as a format refusal`() {
        assertTrue(CloudError.forHttpStatus(400, "X").isFormatRefusal)
        assertTrue(CloudError.forHttpStatus(415, "X").isFormatRefusal)
        assertFalse(CloudError.forHttpStatus(404, "X").isFormatRefusal) // a model the service does not have
        assertFalse(CloudError.forHttpStatus(422, "X").isFormatRefusal)
        assertFalse(CloudError.forHttpStatus(429, "X").isFormatRefusal)
        assertFalse(CloudError.forHttpStatus(401, "X").isFormatRefusal)
        assertFalse(CloudError.notSetUp("X").isFormatRefusal)
    }

    @Test
    fun `a refusal is remembered for that address and model only`() {
        var now = 1_000L
        val refusals = ContainerRefusals(prefs, { now })
        assertFalse(refusals.isRefused(custom))
        refusals.markRefused(custom)
        assertTrue(refusals.isRefused(custom))
        assertFalse(refusals.isRefused(custom.copy(model = "other")))
        assertFalse(refusals.isRefused(custom.copy(baseUrl = "http://10.0.0.6:8000/v1")))
        now += 1
        refusals.clear(custom)
        assertFalse(refusals.isRefused(custom))
    }

    @Test
    fun `a refusal expires so a service that learns the container is tried again`() {
        var now = 1_000L
        val refusals = ContainerRefusals(prefs, { now })
        refusals.markRefused(custom)
        now += ContainerRefusals.EXPIRY_MS - 1
        assertTrue(refusals.isRefused(custom))
        now += 2
        assertFalse(refusals.isRefused(custom))
        assertEquals(false, prefs.all.isEmpty()) // still stored; expiry is by age, not removal
    }

    @Test
    fun `a container the service accepted clears an earlier refusal for that service only`() {
        val refusals = ContainerRefusals(prefs, { 1_000L })
        val other = custom.copy(model = "other")
        refusals.markRefused(custom)
        refusals.markRefused(other)
        refusals.noteContainerWorked(custom)
        assertFalse(refusals.isRefused(custom))
        assertTrue(refusals.isRefused(other))
        refusals.noteContainerWorked(custom) // nothing stored: harmless
    }
}
