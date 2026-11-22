/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import com.baba.callvault.data.recordings.ImportedRecording
import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem

/**
 * How a recording is named to the user: contact, else number, else the file name.
 *
 * One rule in one place. It was written out by hand at each call site, and the transcribing sheet got
 * it wrong — showing `20260819_201239.877+0300_in_<name>.ogg` where every other surface said who the
 * call was with.
 *
 * Every name here is bidi-isolated, because this is a Hebrew-speaking user's contacts inside an English
 * UI: without it a name is just characters in whatever direction the surrounding paragraph resolved to,
 * and one of Hebrew-plus-a-digit rendered as "2 גבריאלb". Isolating at the single point every label
 * comes from is what stops that being re-broken at the next call site.
 */
object RecordingLabel {

    /** The best available name for [item], or null when there is no item. */
    fun of(item: RecordingItem?): String? =
        item?.let { BidiText.isolate(it.contactName ?: it.number ?: it.displayName) }

    /**
     * The best available name for [displayName], looked up in [recordings].
     *
     * Falls back to [forName] when the recording is not there: the transcribing sheet learns the name
     * from WorkManager progress, which can outlive the recording, and a raw file name is still better
     * than a blank sheet.
     */
    fun forDisplayName(recordings: List<RecordingItem>, displayName: String): String =
        of(recordings.firstOrNull { it.displayName == displayName }) ?: forName(displayName)

    /**
     * The name for [displayName] worked out from the file name alone, plus one contact lookup per call through
     * [lookupContact] (given the number, or a number-looking contact slot) — no folder listing (review F5: the
     * "transcript ready" notification listed 136 recordings just to find one name). Same order as [of]:
     * contact, else number, else the file's own label.
     */
    fun resolve(displayName: String, lookupContact: (String) -> String?): String {
        val parsed = RecordingsRepository.parseName(displayName)
        val key = parsed.number?.takeUnless { it.isBlank() }
            ?: parsed.contactName?.takeIf { RecordingsRepository.looksLikePhoneNumber(it) }
        val contact = key?.let { runCatching { lookupContact(it) }.getOrNull() }?.takeUnless { it.isBlank() }
        val best = contact ?: parsed.contactName ?: parsed.number
        return best?.let { BidiText.isolate(it) } ?: forName(displayName)
    }

    /**
     * The best name derivable from the file name alone, for a row whose recording is gone or was
     * never in the list — an orphaned transcript, an orphaned summary, a transcribe-only import.
     *
     * An import carries the source file's own name as its label, so that is what is shown: otherwise
     * the row reads `20260916_101010.123+0300_import_voice note.opus`, which is our bookkeeping
     * rather than anything the user recognises. Everything else falls back to the raw name, which is
     * all there is.
     */
    fun forName(displayName: String): String =
        BidiText.isolate(ImportedRecording.labelOf(displayName) ?: displayName)
}
