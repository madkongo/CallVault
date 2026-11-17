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

/**
 * The decisions behind the recording card (BabApps item 137, design L), kept free of Compose so they
 * can be tested: which badge, which direction label, which storage icons, and what a tap on the photo
 * does.
 */
object RecordingCardLogic {

    /** Colour family of the app label under the name. The colours themselves live in the UI. */
    enum class AppTone { WHATSAPP, TELEGRAM, NEUTRAL }

    /**
     * The tone for an app label. Matched on the app's own name, ignoring case, so "WhatsApp Business"
     * stays green; anything else (Signal, Phone, an unknown app) is grey rather than guessed at.
     */
    fun appTone(label: String?): AppTone {
        val l = label?.lowercase() ?: return AppTone.NEUTRAL
        return when {
            "whatsapp" in l -> AppTone.WHATSAPP
            "telegram" in l -> AppTone.TELEGRAM
            else -> AppTone.NEUTRAL
        }
    }

    /**
     * The app logo goes on the photo only for an app call. A carrier call gets no logo: the "Phone"
     * label under the name says it, and a phone glyph on every row is noise.
     */
    fun showsLogoBadge(voipApp: String?): Boolean = voipApp != null

    /**
     * The app label under the name: the app's name for an app call, [phoneLabel] for a carrier call,
     * else [fallback] (an import or an unclassified row has nothing honest to say, so it says nothing
     * unless the "All calls" list supplied its own wording).
     */
    fun appLabel(voipApp: String?, isCarrierCall: Boolean, phoneLabel: String, fallback: String?): String? =
        voipApp ?: if (isCarrierCall) phoneLabel else fallback

    /** Which direction label to draw, or null (no label at all) when the direction is unknown. */
    enum class DirectionMark { IN, OUT }

    fun directionMark(direction: RecordingDirection?): DirectionMark? = when (direction) {
        RecordingDirection.INCOMING -> DirectionMark.IN
        RecordingDirection.OUTGOING -> DirectionMark.OUT
        null -> null
    }

    /** The colour of one storage glyph. */
    enum class StorageTone { GREEN, GREY, BLUE }

    /** Which glyphs follow the name, and the colour of each. A null tone means that glyph is absent. */
    data class StorageIcons(val phone: StorageTone?, val cloud: StorageTone?) {
        val any: Boolean get() = phone != null || cloud != null
    }

    /**
     * Green phone + cloud = both places, grey phone = this phone only, blue cloud = Drive only.
     * Green is "safe in both"; the single-place states are deliberately not green.
     */
    fun storageIcons(source: RecordingSource): StorageIcons = when (source) {
        RecordingSource.BOTH -> StorageIcons(StorageTone.GREEN, StorageTone.GREEN)
        RecordingSource.LOCAL -> StorageIcons(StorageTone.GREY, null)
        RecordingSource.DRIVE -> StorageIcons(null, StorageTone.BLUE)
    }

    /** What a tap on the contact photo does. */
    enum class PhotoTap {
        /** While selecting, the photo is part of the card: it toggles the selection. */
        TOGGLE_SELECTION,
        /** A known contact: open it in the phone's contacts app. */
        OPEN_CONTACT,
        /** A number with no contact: offer to add one. */
        ADD_CONTACT,
        /** No number (an import, an app call without one): nothing to open. */
        NOTHING,
    }

    fun photoTap(selectionMode: Boolean, number: String?, contactKnown: Boolean): PhotoTap = when {
        selectionMode -> PhotoTap.TOGGLE_SELECTION
        number.isNullOrBlank() -> PhotoTap.NOTHING
        contactKnown -> PhotoTap.OPEN_CONTACT
        else -> PhotoTap.ADD_CONTACT
    }

    /** The letter shown on the tinted circle when there is no photo; null when the name has none. */
    fun initialOf(contactName: String?): String? =
        contactName?.firstOrNull { it.isLetter() }?.uppercase()
}
