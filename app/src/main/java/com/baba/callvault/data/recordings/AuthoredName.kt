/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import com.baba.callvault.utils.FILE_NAME_TEMPLATE_PRESETS
import com.baba.callvault.utils.FileNameStamp
import com.baba.callvault.utils.RecordingFileNameFormatter
import com.baba.callvault.utils.RecordingFileNameFormatter.FileNamePlaceholder

/**
 * Whether a file name is one CallVault wrote: the proof of authorship the retention sweep needs before it deletes a
 * file the catalog does not know (review C10).
 *
 * Kept apart from [RecordingsRepository.parseName], which is lenient on purpose: the list should show a date for
 * any name it can, and since 2.5.2 that includes any day+time stamp found anywhere ("audio_2024-03-05_14-22-10").
 * That leniency is fine for display and wrong for deletion, because the user's folder may hold other apps' audio.
 *
 * So the whole name must match a CallVault grammar, anchored at both ends:
 *  - a built-in preset that uses `{date}`, whose stamp (`yyyyMMdd_HHmmss.SSS±hhmm`) no other app is known to write;
 *  - the app-call grammar `{date}[_in|_out]_voip[-App][_caller]`;
 *  - the template active now, compiled tag by tag. This is the only way a `{day}`/`{time}` or Honor name qualifies,
 *    and it is only as strict as that template: one like `{contact_name}_{day-}_{time-}` also matches a foreign
 *    `audio_2024-03-05_14-22-10`, which nothing in the name can tell apart.
 * Names written under an earlier custom template stop ageing out: the safe direction (a pull-to-refresh catalogues
 * them, and the catalogued pass needs no name test).
 */
internal object AuthoredName {

    /** `{date}` exactly as [RecordingFileNameFormatter] writes it (`yyyyMMdd_HHmmss.SSSZ`, Western digits). */
    private const val FULL_STAMP = """\d{8}_\d{6}\.\d{3}[+-]\d{4}"""

    private val VOIP = Regex("""$FULL_STAMP(?:_(?:in|out))?_voip(?:-[^_]*)?(?:_.*)?""")

    private val TAG = Regex("""\{[^{}]*\}""")

    private val BUILT_IN_DATE_PRESETS = FILE_NAME_TEMPLATE_PRESETS.map { it.template }
        .filter { it.contains(FileNamePlaceholder.DATE.tag) }
        .map(::compile)

    /** True when [displayName] (with its extension) matches a grammar CallVault writes; see the class comment. */
    fun isCallVaultName(displayName: String, activeTemplate: String): Boolean {
        val base = displayName.substringBeforeLast('.')
        if (VOIP.matches(base)) return true
        if (BUILT_IN_DATE_PRESETS.any { it.matches(base) }) return true
        return compile(activeTemplate).matches(base)
    }

    /** [template] as an anchored pattern: each tag by the shape the formatter writes, everything else literal. */
    internal fun compile(template: String): Regex {
        val pattern = StringBuilder()
        var at = 0
        TAG.findAll(template).forEach { m ->
            pattern.append(Regex.escape(template.substring(at, m.range.first)))
            pattern.append(patternFor(m.value))
            at = m.range.last + 1
        }
        pattern.append(Regex.escape(template.substring(at)))
        return Regex(pattern.toString())
    }

    private fun patternFor(tag: String): String = when (tag) {
        FileNamePlaceholder.DATE.tag -> FULL_STAMP
        RecordingFileNameFormatter.DATE_COMPACT_TAG -> """\d{14}"""
        FileNameStamp.DAY_TAG -> """\d{8}"""
        FileNameStamp.DAY_DASHED_TAG -> """\d{4}-\d{2}-\d{2}"""
        FileNameStamp.TIME_TAG -> """\d{6}"""
        FileNameStamp.TIME_DASHED_TAG -> """\d{2}-\d{2}-\d{2}"""
        FileNamePlaceholder.DIRECTION.tag -> "(?:in|out)"
        FileNamePlaceholder.CROSS_COUNTRY.tag -> "(?:true|false)"
        // Free text (a number, a contact name), possibly empty.
        FileNamePlaceholder.PHONE_NUMBER.tag, FileNamePlaceholder.CONTACT_NAME.tag -> ".*"
        // A tag the formatter does not know is written literally, as the formatter leaves it.
        else -> Regex.escape(tag)
    }
}
