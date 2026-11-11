/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.RecordingState

/**
 * Quick Settings tile that toggles the master recording switch — ACTIVE ⇄ STOPPED — from the
 * notification shade, without opening the app (a tester's one-tap request). The side effects of turning it
 * off (tear the daemon down) or on (bring it back) run on [RecordingSwitch], off the main thread.
 */
class RecordingTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render(AppPreferences(this).getRecordingState())
    }

    override fun onClick() {
        super.onClick()
        val next = when (AppPreferences(this).getRecordingState()) {
            RecordingState.ACTIVE -> RecordingState.STOPPED
            RecordingState.STOPPED -> RecordingState.ACTIVE
        }
        render(next) // reflect the user's tap at once; the lifecycle work follows off-thread
        val app = applicationContext
        Thread({ RecordingSwitch.apply(app, next) }, "cv-tile-apply").apply { isDaemon = true }.start()
    }

    private fun render(state: RecordingState) {
        val tile = qsTile ?: return
        tile.state = if (state == RecordingState.ACTIVE) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.subtitle = getString(
            when (state) {
                RecordingState.ACTIVE -> R.string.tile_recording_on
                RecordingState.STOPPED -> R.string.tile_recording_stopped
            }
        )
        tile.icon = Icon.createWithResource(this, R.drawable.ic_mic)
        tile.updateTile()
    }
}
