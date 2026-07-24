/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/**
 * One recording pipeline run by the privileged daemon. Two implementations:
 *  - [DirectAudioRecorderSession] — direct `AudioRecord` → `MediaCodec` → `MediaMuxer` (FAST: no child
 *    process, no scrcpy jar, captures from the first millisecond — the preferred path).
 *  - [RecorderSession] — the scrcpy-server child pipeline (fallback for sources/codecs the direct path
 *    can't handle, e.g. output/playback capture or a device missing the Opus encoder).
 *
 * [RecorderServer] chooses the direct path when [DirectAudioRecorderSession.supports] is true and falls
 * back to scrcpy otherwise. Both are single-use, not thread-safe; the server serialises lifecycle calls.
 */
internal interface RecordingSession {
    /** Starts capture; returns once the pipeline is running (capture continues on its own thread). */
    fun start()

    /** Tears down the pipeline and finalises the output container. Idempotent, best-effort. */
    fun stop()
}
