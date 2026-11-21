package com.baba.callvault.system.updates

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** C3: an install held back by a call is remembered; after the call ONE "ready, tap to install" notice, never an install. */
class UpdateReadyNoticeTest {

    private class Fixture(var callFree: Boolean = false) {
        var pending = false
        var posted = 0
        var installs = 0 // nothing in the notice touches this: the only installer is the user's tap
        val notice = UpdateReadyNotice(
            isPending = { pending },
            setPending = { pending = it },
            isCallFree = { callFree },
            post = { posted++ },
        )
    }

    @Test fun `deferral during a call posts nothing and nothing installs`() {
        val f = Fixture(callFree = false)
        f.notice.noteDeferred()
        f.notice.onCallEnded() // gate still says busy
        assertTrue(f.pending)
        assertEquals(0, f.posted)
        assertEquals(0, f.installs)
    }

    @Test fun `ready notice posts once after the call ends`() {
        val f = Fixture()
        f.notice.noteDeferred()
        f.callFree = true
        f.notice.onCallEnded()
        f.notice.onCallEnded()
        f.notice.onCallEnded()
        assertEquals(1, f.posted)
        assertFalse(f.pending)
    }

    @Test fun `call ending with no deferred install posts nothing`() {
        val f = Fixture(callFree = true)
        f.notice.onCallEnded()
        assertEquals(0, f.posted)
    }

    @Test fun `tap during a second call defers again and the notice comes back after that call`() {
        val f = Fixture(callFree = true)
        f.notice.noteDeferred()
        f.notice.onCallEnded()
        assertEquals(1, f.posted)
        // user taps while another call is on: the install is deferred again
        f.callFree = false
        f.notice.noteDeferred()
        f.notice.onCallEnded()
        assertEquals(1, f.posted)
        f.callFree = true
        f.notice.onCallEnded()
        assertEquals(2, f.posted)
        assertFalse(f.pending)
    }

    @Test fun `banner is not busy once the deferred install work has finished`() {
        assertFalse(UpdateInstallWorker.isBannerBusy(listOf(WorkInfo.State.SUCCEEDED)))
        assertFalse(UpdateInstallWorker.isBannerBusy(emptyList()))
        assertTrue(UpdateInstallWorker.isBannerBusy(listOf(WorkInfo.State.RUNNING)))
        assertTrue(UpdateInstallWorker.isBannerBusy(listOf(WorkInfo.State.ENQUEUED)))
    }
}
