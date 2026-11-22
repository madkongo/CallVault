/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import android.net.Uri
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.transcription.AudioDecoder
import com.baba.callvault.system.diagnostics.CallEndLog
import com.baba.callvault.utils.AppLogger

/**
 * Review N9: a rescued (cut-off) recording shows its OWN length in the list. The call log knows only the one call it
 * recorded (28 s for a file of 128 s), so the rescue reads the length off the saved file and remembers it, as an
 * import does. Never throws: a failed read is logged and the list probes the file later as it always did.
 */
object RescuedDuration {
    private const val TAG = "CV:RescuedDuration"

    suspend fun store(
        context: Context, name: String, uri: Uri,
        probe: (Context, Uri) -> Long = AudioDecoder::durationMs,
    ) {
        val ms = runCatching { probe(context, uri) }
            .onFailure { CallEndLog.record(context, "cut-off rescue: length of ${CallEndLog.safeName(name)} unreadable ${CallEndLog.describe(it)}; the list will read it later") }
            .getOrNull() ?: return
        if (ms <= 0L) {
            CallEndLog.record(context, "cut-off rescue: length of ${CallEndLog.safeName(name)} read as $ms ms; not stored, the list will read it later")
            return
        }
        val seconds = (ms + 500) / 1000
        RecordingCatalog.setDuration(context, name, seconds)
        AppLogger.i(TAG, "Stored the length of the rescued $name: ${seconds}s")
    }
}
