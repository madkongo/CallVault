package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Test

class RecorderCallerTest {

    private fun frame(cls: String, method: String) = StackTraceElement(cls, method, "F.kt", 1)

    @Test
    fun names_the_first_frame_outside_the_bring_up_machinery() {
        val frames = arrayOf(
            frame("java.lang.Throwable", "<init>"),
            frame("com.baba.callvault.server.RecorderBackend", "ensureRunning"),
            frame("com.baba.callvault.services.recording.AudioRecordingEngine", "startHandoffPipeline"),
            frame("com.baba.callvault.services.recording.RecordingForegroundService", "onStartCommand"),
        )
        assertEquals("AudioRecordingEngine.startHandoffPipeline", RecorderCaller.of(frames))
    }

    @Test
    fun says_unknown_when_nothing_else_is_on_the_stack() {
        assertEquals("unknown", RecorderCaller.of(arrayOf(frame("java.lang.Thread", "run"))))
    }
}
