/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.phone

import android.content.Context
import com.baba.callvault.integrations.adb.UsbDebuggingEvents

/**
 * Where the profile reaches a human: the debug report header, and the always-on log when something changed.
 * No periodic work: [evaluateAtStart] runs once per process start.
 */
object PhoneProfileReport {

    private const val PREFS = "cv_phone_profile"
    private const val KEY_HASH = "hash"
    private const val KEY_ACTIVE = "active"

    /** Report lines for a profile; pure. */
    fun lines(profile: PhoneProfile): List<String> {
        val active = PhoneRules.evaluate(profile)
        val inactive = PhoneRules.entries.count { it.applies != null } - active.size
        return buildList {
            add("--- Phone profile ---")
            add(profile.logLine())
            add("profile id: ${profile.hash()}")
            add("--- Active phone rules ---")
            add(if (active.isEmpty()) "none" else active.joinToString("\n") { "${it.id} ${it.severity?.name ?: ""}".trim() })
            add("checked, not active: $inactive; rules without a profile check yet are catalogued only")
        }
    }

    /** Report lines read from the phone now; never throws. */
    fun linesNow(context: Context): List<String> =
        runCatching { lines(PhoneProfileReader.read(context)) }
            .getOrElse { listOf("--- Phone profile ---", "unavailable: ${it.javaClass.simpleName}") }

    /** What to write to the always-on log: nothing when [oldHash] equals the profile's. Pure. */
    fun changeLines(oldHash: String?, oldActive: Set<String>, profile: PhoneProfile): List<String> {
        val activeNow = PhoneRules.evaluate(profile).map { it.id }.toSet()
        return buildList {
            if (oldHash != profile.hash()) add("profile: ${profile.hash()} ${profile.logLine()}")
            (activeNow - oldActive).sorted().forEach { add("rule: $it on") }
            (oldActive - activeNow).sorted().forEach { add("rule: $it off") }
        }
    }

    /** Called once at process start, off the main thread. Writes only on change; never throws. */
    fun evaluateAtStart(context: Context) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val profile = PhoneProfileReader.read(context)
            val oldActive = prefs.getStringSet(KEY_ACTIVE, emptySet()).orEmpty()
            val lines = changeLines(prefs.getString(KEY_HASH, null), oldActive, profile)
            lines.forEach { UsbDebuggingEvents.record(context, it) }
            if (lines.isNotEmpty()) {
                prefs.edit()
                    .putString(KEY_HASH, profile.hash())
                    .putStringSet(KEY_ACTIVE, PhoneRules.evaluate(profile).map { it.id }.toSet())
                    .apply()
            }
        }.onFailure { com.baba.callvault.utils.AppLogger.w("CV:PhoneProfile", "Phone profile check failed: \${it.javaClass.simpleName}: \${it.message}") }
    }
}
