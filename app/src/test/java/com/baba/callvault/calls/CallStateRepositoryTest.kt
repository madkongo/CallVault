package com.baba.callvault.calls

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallStateRepositoryTest {
    @Test fun update_and_clear_current_call() = runBlocking {
        CallStateRepository.update(UiCall("1", CallEvent.Phase.ACTIVE, CallDirection.INCOMING, false, false))
        assertEquals("1", CallStateRepository.current.first()!!.number)
        CallStateRepository.update(null)
        assertNull(CallStateRepository.current.first())
    }

    @Test fun set_recording_updates_flag() = runBlocking {
        CallStateRepository.update(UiCall("1", CallEvent.Phase.ACTIVE, CallDirection.INCOMING, false, false))
        CallStateRepository.setRecording(true)
        assertEquals(true, CallStateRepository.current.first()!!.isRecording)
    }
}
