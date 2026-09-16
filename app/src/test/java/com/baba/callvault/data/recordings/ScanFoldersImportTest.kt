/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import com.baba.callvault.data.StorageTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a catalog re-seed takes out of the device folder.
 *
 * The re-seed is destructive in effect even though it deletes nothing: whatever it does not pick up
 * is gone from the list, and the user has no way of knowing the file is still on the phone. So the
 * rule is worth pinning even though it is one line.
 */
class ScanFoldersImportTest {

    private val call = "20260916_101010.123+0300_in_0501234567.ogg"
    private val import = "20260916_101010.123+0300_import_voice-note.ogg"

    @Test
    fun `everything in the device folder counts when recordings are kept there`() {
        assertTrue(RecordingsRepository.seedsFromDevice(StorageTarget.LOCAL, call))
        assertTrue(RecordingsRepository.seedsFromDevice(StorageTarget.BOTH, call))
    }

    @Test
    fun `a leftover call is not raised from the dead in Drive-only mode`() {
        // DRIVE deletes the local copy after each sync, so a call still sitting in the device folder
        // is one the sync deliberately removed — or is about to.
        assertFalse(RecordingsRepository.seedsFromDevice(StorageTarget.DRIVE, call))
    }

    @Test
    fun `an import survives a re-seed in Drive-only mode`() {
        // The case this exists for. An import never goes to Drive, so enumerating the Drive folder
        // alone would drop it from the list while the file sat safely in the device folder.
        assertTrue(RecordingsRepository.seedsFromDevice(StorageTarget.DRIVE, import))
    }

    @Test
    fun `a call with a contact called Important is still a call`() {
        assertFalse(
            RecordingsRepository.seedsFromDevice(
                StorageTarget.DRIVE,
                "20260916_101010.123+0300_in_Important.ogg",
            )
        )
    }

    @Test
    fun `every format the importer writes is recognised by the folder scan`() {
        // enumerateFolder knows two extensions by name and otherwise trusts the provider's MIME
        // type, which is frequently application/octet-stream for a file whose name says plainly what
        // it is. An extension the importer can write but the scan does not know would import fine
        // and then vanish on the next re-seed.
        for (extension in ImportableAudio.ACCEPTED_EXTENSIONS) {
            assertTrue(
                "the folder scan does not recognise .$extension",
                RecordingsRepository.isAudioName("20260916_101010.123+0300_import_note.$extension"),
            )
        }
    }
}
