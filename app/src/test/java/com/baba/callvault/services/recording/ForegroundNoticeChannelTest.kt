package com.baba.callvault.services.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Review N22 (vivo V2546A, Android 17, 2026-11-20): an app-only user never recorded a phone call, so the
 * "recording_channel_service" channel (created only by the carrier service) did not exist. Since review C23 the
 * app-call notice is posted through the keep-alive's startForeground, and Android answers a foreground notice on a
 * missing channel with CannotPostForegroundServiceNotificationException ("Bad notification for startForeground"),
 * killing the app at the start of every app call. notify() only drops such a notice, which is why 2.5.1 never crashed.
 */
class ForegroundNoticeChannelTest {

    @Test
    fun `a notice on a missing channel is never posted as the foreground notice`() {
        assertEquals(ForegroundNoticeChannel.Verdict.MISSING_CHANNEL, ForegroundNoticeChannel.verdict("recording_channel_service") { false })
    }

    @Test
    fun `a notice on an existing channel is posted as the foreground notice`() {
        assertEquals(ForegroundNoticeChannel.Verdict.OK, ForegroundNoticeChannel.verdict("recording_channel_service") { true })
    }

    @Test
    fun `a notice with no channel is never posted as the foreground notice`() {
        assertEquals(ForegroundNoticeChannel.Verdict.MISSING_CHANNEL, ForegroundNoticeChannel.verdict(null) { true })
    }

    @Test
    fun `a failing lookup counts as missing, never as present`() {
        assertEquals(ForegroundNoticeChannel.Verdict.MISSING_CHANNEL, ForegroundNoticeChannel.verdict("x") { error("binder") })
    }

    // --- Light source guards, only where no seam exists. The decisions themselves are behaviour-tested in
    // ForegroundStartNoticeTest, CallMonitorForegroundNoticeTest, RecordingChannelsEnsureTest and
    // VoipRecordingNotificationTest. ---

    private fun source(path: String): String {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        return File(root, path).readText()
    }

    /** Application.onCreate runs once per test process under Robolectric, before any test can reset the cache. */
    @Test
    fun `the recording channels are created at app start, not only by the phone-call service`() {
        assertTrue(source("com/baba/callvault/CallVaultApplication.kt").contains("RecordingNotificationHelper.ensureChannels("))
    }

    /** The keep-alive cannot be started under Robolectric (ADB, daemon, observers), so its wiring is read. */
    @Test
    fun `every startForeground in the keep-alive goes through a channel check or its own ensured channel`() {
        val text = source("com/baba/callvault/services/recording/DaemonKeepAliveService.kt")
        assertTrue("never the held notice unchecked", !text.contains("contentForKeepAlive("))
        assertTrue(text.contains("ForegroundNoticeChannel.noticeForStart("))
        val poster = text.substring(text.indexOf("SharedStatusNotice.foregroundPoster = {"))
        assertTrue(poster.substring(0, 300).contains("ForegroundNoticeChannel.postAsForeground("))
        val repostHeld = text.substring(text.indexOf("private fun repostHeldAsForeground"))
        assertTrue(repostHeld.substring(0, 300).contains("ForegroundNoticeChannel.postAsForeground("))
        val ready = text.substring(text.indexOf("private fun repostAsForeground"))
        val ensure = ready.indexOf("ensureOwnChannel()")
        assertTrue("ensureOwnChannel must come before the startForeground of Ready", ensure in 0 until ready.indexOf("startForeground("))
        // Exactly the three sites above plus the checked helper: a new raw startForeground must be routed too.
        assertTrue(Regex("""\bstartForeground\(""").findAll(text).count() == 3)
    }
}
