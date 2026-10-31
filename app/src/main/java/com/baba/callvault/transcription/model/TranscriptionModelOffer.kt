/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.model

/**
 * Which speech models the picker lists (GitHub #55).
 *
 * A general tier is always listed. A single-language tier — one with a [TranscriptionModel.language] —
 * is listed when that language is pinned, and **also** while it is the selected tier or is on the
 * phone, whatever the language says:
 *
 * - **Selected:** changing the language away from Persian does not switch the model behind the
 *   user's back. Falling back to the default would re-point them at a tier they may not have
 *   downloaded (transcription then stops until they notice), and hiding a selected tier would make
 *   the dropdown name a model other than the one that runs. So it stays selected and visible, and
 *   the user switches when they choose to.
 * - **Installed:** the delete row only ever offers the *selected* tier, so a hidden tier that is on
 *   the phone would be a 574 MB file nobody can reach — the stranding [TranscriptionModel] warns
 *   about for removed entries.
 *
 * Pure so the rule is tested on the JVM; the composable only supplies the three inputs.
 */
object TranscriptionModelOffer {

    /**
     * @param pinnedLanguage The pinned whisper language code, or null for auto-detect.
     * @param selectedId     The stored model id.
     * @param installedIds   Ids of the models complete on the phone.
     * @return The catalogue in its declared order, minus single-language tiers nothing asks for.
     */
    fun offered(
        pinnedLanguage: String?,
        selectedId: String?,
        installedIds: Set<String>,
    ): List<TranscriptionModel> = TranscriptionModel.entries.filter { model ->
        model.language == null ||
            model.language == pinnedLanguage ||
            model.id == selectedId ||
            model.id in installedIds
    }
}
