/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import com.baba.callvault.utils.AppLogger

/**
 * One log line per delete attempt, so a copy that survives never does so silently. File names only: they are what
 * the recordings folder holds, and no number or contact name is added here.
 */
internal object DeleteLog {
    const val LOCAL = "local"
    const val DRIVE = "Drive"

    enum class Outcome { DELETED, REFUSED, THREW, ABSENT }

    /** "audio", or "sidecar .md" / "sidecar .json" for the text and details files beside it. */
    fun kindOf(name: String?): String {
        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return if (ext == "md" || ext == "json") "sidecar .$ext" else "audio"
    }

    fun line(copy: String, name: String?, outcome: Outcome, detail: String? = null): String =
        "Delete $copy ${kindOf(name)} '${name ?: "?"}': " + when (outcome) {
            Outcome.DELETED -> "deleted"
            Outcome.REFUSED -> "refused (provider's delete() returned false); the file is still there"
            Outcome.THREW -> "failed: ${detail ?: "unknown error"}"
            Outcome.ABSENT -> "not found"
        }

    fun log(tag: String, copy: String, name: String?, outcome: Outcome, detail: String? = null) {
        val text = line(copy, name, outcome, detail)
        when (outcome) {
            Outcome.DELETED -> AppLogger.i(tag, text)
            Outcome.REFUSED, Outcome.THREW -> AppLogger.w(tag, text)
            Outcome.ABSENT -> Unit
        }
    }
}
