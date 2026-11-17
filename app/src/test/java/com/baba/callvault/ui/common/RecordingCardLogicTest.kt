/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import com.baba.callvault.data.recordings.RecordingDirection
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingSource
import com.baba.callvault.ui.common.RecordingCardLogic.AppTone
import com.baba.callvault.ui.common.RecordingCardLogic.DirectionMark
import com.baba.callvault.ui.common.RecordingCardLogic.PhotoTap
import com.baba.callvault.ui.common.RecordingCardLogic.StorageTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingCardLogicTest {

    @Test fun appToneIsMatchedOnTheAppsName() {
        assertEquals(AppTone.WHATSAPP, RecordingCardLogic.appTone("WhatsApp"))
        assertEquals(AppTone.WHATSAPP, RecordingCardLogic.appTone("whatsapp business"))
        assertEquals(AppTone.TELEGRAM, RecordingCardLogic.appTone("Telegram"))
        assertEquals(AppTone.NEUTRAL, RecordingCardLogic.appTone("Signal"))
        assertEquals(AppTone.NEUTRAL, RecordingCardLogic.appTone("Phone call"))
        assertEquals(AppTone.NEUTRAL, RecordingCardLogic.appTone(null))
    }

    @Test fun logoBadgeOnlyForAnAppCall() {
        assertTrue(RecordingCardLogic.showsLogoBadge("WhatsApp"))
        assertFalse(RecordingCardLogic.showsLogoBadge(null))
    }

    @Test fun appLabelPrefersTheAppThenPhoneThenFallback() {
        assertEquals("Telegram", RecordingCardLogic.appLabel("Telegram", true, "Phone", "x"))
        assertEquals("Phone", RecordingCardLogic.appLabel(null, true, "Phone", "x"))
        assertEquals("x", RecordingCardLogic.appLabel(null, false, "Phone", "x"))
        assertNull(RecordingCardLogic.appLabel(null, false, "Phone", null))
    }

    @Test fun directionLabelAbsentWhenUnknown() {
        assertEquals(DirectionMark.IN, RecordingCardLogic.directionMark(RecordingDirection.INCOMING))
        assertEquals(DirectionMark.OUT, RecordingCardLogic.directionMark(RecordingDirection.OUTGOING))
        assertNull(RecordingCardLogic.directionMark(null))
    }

    @Test fun storageIconsFollowTheSource() {
        val both = RecordingCardLogic.storageIcons(RecordingSource.BOTH)
        assertEquals(StorageTone.GREEN, both.phone)
        assertEquals(StorageTone.GREEN, both.cloud)

        val local = RecordingCardLogic.storageIcons(RecordingSource.LOCAL)
        assertEquals(StorageTone.GREY, local.phone)
        assertNull(local.cloud)

        val drive = RecordingCardLogic.storageIcons(RecordingSource.DRIVE)
        assertNull(drive.phone)
        assertEquals(StorageTone.BLUE, drive.cloud)

        RecordingSource.entries.forEach { assertTrue(RecordingCardLogic.storageIcons(it).any) }
    }

    @Test fun photoTapDependsOnSelectionAndContact() {
        assertEquals(PhotoTap.TOGGLE_SELECTION, RecordingCardLogic.photoTap(true, "+123", true))
        assertEquals(PhotoTap.TOGGLE_SELECTION, RecordingCardLogic.photoTap(true, null, false))
        assertEquals(PhotoTap.OPEN_CONTACT, RecordingCardLogic.photoTap(false, "+123", true))
        assertEquals(PhotoTap.ADD_CONTACT, RecordingCardLogic.photoTap(false, "+123", false))
        assertEquals(PhotoTap.NOTHING, RecordingCardLogic.photoTap(false, null, false))
        assertEquals(PhotoTap.NOTHING, RecordingCardLogic.photoTap(false, " ", true))
    }

    @Test fun initialSkipsNonLetters() {
        assertEquals("D", RecordingCardLogic.initialOf("dana"))
        assertEquals("G", RecordingCardLogic.initialOf("2 Gabi"))
        assertNull(RecordingCardLogic.initialOf("+972 50"))
        assertNull(RecordingCardLogic.initialOf(null))
    }
}
