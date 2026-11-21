/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.ui.viewmodels.RecordingPlaybackController.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

/** Review C154: Play / seek / skip taps while the track is still loading, or after a failure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordingPlaybackControllerGatingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val uri: Uri = Uri.parse("content://recordings/gate.ogg")

    private fun registerTrack(durationMs: Int) {
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, uri), ShadowMediaPlayer.MediaInfo(durationMs, 0))
    }

    @Test
    fun resume_while_loading_changes_nothing() {
        registerTrack(60_000)
        val c = RecordingPlaybackController()
        c.play(context, uri)
        assertEquals(Phase.LOADING, c.state.value.phase)

        c.resume()

        assertEquals(Phase.LOADING, c.state.value.phase)
    }

    @Test
    fun a_seek_while_loading_is_held_and_applied_when_prepared() {
        registerTrack(60_000)
        val c = RecordingPlaybackController()
        c.play(context, uri)

        c.seekTo(30_000)
        c.skip(10_000) // a skip has no duration to clamp against yet: ignored
        assertEquals(Phase.LOADING, c.state.value.phase)

        shadowOf(android.os.Looper.getMainLooper()).idle()
        org.robolectric.shadows.ShadowLooper.idleMainLooper(5, java.util.concurrent.TimeUnit.SECONDS)

        assertEquals(Phase.PLAYING, c.state.value.phase)
        assertEquals(30_000, c.state.value.positionMs)
        assertEquals(60_000, c.state.value.durationMs)
    }

    @Test
    fun a_seek_in_error_does_nothing_and_resume_loads_the_track_again() {
        // No media registered: the player refuses the source, so the controller lands in ERROR.
        val c = RecordingPlaybackController()
        c.play(context, uri, startAtMs = 12_000)
        assertEquals(Phase.ERROR, c.state.value.phase)

        c.seekTo(5_000)
        assertEquals(Phase.ERROR, c.state.value.phase)
        assertEquals(0, c.state.value.positionMs)

        registerTrack(60_000)
        c.resume()
        assertEquals(Phase.LOADING, c.state.value.phase)
        assertNotNull(c.state.value.activeUri)
        assertEquals(uri, c.state.value.activeUri)
    }

    @Test
    fun resume_when_idle_is_a_no_op() {
        val c = RecordingPlaybackController()
        c.resume()
        c.seekTo(1_000)
        c.skip(10_000)
        assertEquals(Phase.IDLE, c.state.value.phase)
    }
}
