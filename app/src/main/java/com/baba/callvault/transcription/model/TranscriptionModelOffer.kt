/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.model

import com.baba.callvault.transcription.TranscriptionLanguageChoice

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
        // The reverse case (BabApps item 117): a tier that cannot decode the pinned language is hidden,
        // unless it is already the selected one (same reason as above: never rename the model behind
        // the user's back). The run itself refuses that pair with a clear message.
        (model.id == selectedId || canDecode(model, pinnedLanguage)) &&
            (
                model.language == null ||
                    model.language == pinnedLanguage ||
                    model.id == selectedId ||
                    model.id in installedIds
                )
    }

    /**
     * Whether [model] can decode [language]. Only the small tier has a gap: its vocabulary has 99
     * languages and Cantonese (`yue`) is the 100th, added with large-v3.
     */
    fun canDecode(model: TranscriptionModel, language: String?): Boolean =
        !(model == TranscriptionModel.SMALL_Q5_1 && language != null &&
            language in TranscriptionLanguageChoice.NOT_ON_SMALL)

    /** [canDecode] for a stored model id; an unknown id counts as the default tier, as everywhere else. */
    fun canDecodeId(modelId: String?, language: String?): Boolean =
        canDecode(modelId?.let { TranscriptionModel.fromId(it) } ?: TranscriptionModel.DEFAULT, language)

    /**
     * The language codes to list while [modelId] is the selected model. A code the model cannot decode
     * is dropped, unless it is [selectedLanguage] (it stays visible so the field never names another
     * language than the one in force).
     *
     * With a cloud service the on-device model is not what decodes, so nothing is hidden (the runner's own
     * rule: `isCloud || canDecodeId(...)`, review C159).
     */
    fun languagesOffered(
        all: List<String?>,
        modelId: String?,
        selectedLanguage: String?,
        isCloud: Boolean = false,
    ): List<String?> =
        all.filter { isCloud || it == selectedLanguage || canDecodeId(modelId, it) }
}
