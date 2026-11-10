package com.baba.callvault.system.interop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptSidecarTest {

    @Test
    fun sidecarNameReplacesTheExtension() {
        assertEquals("call_in_1.md", TranscriptSidecar.sidecarNameFor("call_in_1.ogg"))
    }

    // A .md name maps to itself, so replacing it would delete the sidecar it should keep.
    @Test
    fun aSidecarNameIsNeverTreatedAsARecording() {
        assertTrue(TranscriptSidecar.isSidecarName("call_in_1.md"))
        assertTrue(TranscriptSidecar.isSidecarName("Катя@x.MD"))
        assertEquals("call_in_1.md", TranscriptSidecar.sidecarNameFor("call_in_1.md"))
        assertFalse(TranscriptSidecar.isSidecarName("call_in_1.ogg"))
    }
}
