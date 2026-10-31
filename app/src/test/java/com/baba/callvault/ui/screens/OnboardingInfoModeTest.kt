/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Onboarding (the wizard and the permissions screen) keeps long explanations behind an (i) button, like the
 * new Settings. Read from source, like [ScopedSettingsScreenTest], because the rule is about which
 * composables are called with which strings.
 *
 * The failure message of the inline scan is the list of what is still printed inline.
 *
 * What the scan catches, per file:
 *  - `R.string.key` whose name has a `description`/`desc`/`body`/`hint`/`note`/`explanation` segment, used as the
 *    argument of `stringResource(...)` / `getString(...)` / `Toast`, whether written in place, through a
 *    `when` inside the call, through a local `val x = R.string.key` (or `if`/`when` expression on one line), or
 *    through a helper `fun xRes(...): Int = when { ... R.string.key ... }` called as `stringResource(xRes(..))`.
 *  - It is allowed only when the first real enclosing call is info-aware ([INFO_AWARE_CALLEES]); anything else
 *    (`Text(`, `NoteCard(`, `ToggleCard(`, a Toast, ...) is an offender.
 * What it does NOT catch: strings read into a variable before use (`val s = context.getString(..)`), keys
 * passed through a function parameter, keys whose name has none of the segments (e.g. a `*_subtitle`), and
 * text built by a helper in another file.
 */
class OnboardingInfoModeTest {

    private val screensDir = listOf(
        "src/main/java/com/baba/callvault/ui/screens",
        "app/src/main/java/com/baba/callvault/ui/screens",
    ).map(::File).first { it.isDirectory }

    private fun source(file: File) = file.readText()

    /** Files whose content is onboarding. Add a file here when onboarding gains one. */
    private val scannedFiles: List<File>
        get() {
            val wizard = File(screensDir, "wizard").listFiles { f -> f.extension == "kt" }.orEmpty()
            val named = listOf("WizardScreen.kt", "PermissionsScreen.kt", "ShizukuOfferCard.kt")
                .map { File(screensDir, it) }
            // AdbPermissionCard lives in PermissionsScreen.kt today; if it moves out, it is still scanned.
            val adb = screensDir.listFiles { f -> f.extension == "kt" && f.name.startsWith("AdbPermission") }.orEmpty()
            return (wizard.toList() + named + adb).distinct().sortedBy { it.path }
        }

    @Test
    fun `wizard and permissions content is rendered in info mode`() {
        for (name in listOf("WizardScreen.kt", "PermissionsScreen.kt")) {
            assertTrue(
                "$name must wrap its content in LocalSettingsDescriptionsAsInfo provides true",
                source(File(screensDir, name)).contains("LocalSettingsDescriptionsAsInfo provides true"),
            )
        }
    }

    @Test
    fun `no onboarding card prints an explanation inline`() {
        val offenders = mutableListOf<String>()
        for (file in scannedFiles) offenders += offendersIn(file.name, source(file))
        // The toggle shared with Settings: SettingsToggleRow is info-aware, so it must come out clean.
        val reliability = source(File(screensDir, "SettingsReliabilityRows.kt"))
        val start = reliability.indexOf("fun WirelessDebuggingEnforceToggle(")
        assertTrue("WirelessDebuggingEnforceToggle not found", start >= 0)
        val end = reliability.indexOf("/**", start).let { if (it < 0) reliability.length else it }
        offenders += offendersIn("SettingsReliabilityRows.kt", reliability.substring(0, end), from = start)

        assertTrue(
            "Onboarding prints these explanations inline; put each behind (i) " +
                "(${offenders.size}):\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `every wizard info button has a non-empty explanation`() {
        // An (i) that opens an empty dialog is worse than none: catch a literal blank string passed as
        // the explanation of an info-bearing call.
        val blank = Regex(
            """(?:explanation|infoExtra)\s*=\s*"\s*"|""" +
                """(?:SettingsInfoButton|WithInfo)\s*\([^)]*?(?:explanation\s*=\s*|,\s*)"\s*"\s*[,)]|""" +
                """description\s*=\s*"\s*\""""
        )
        val offenders = scannedFiles.flatMap { file ->
            val text = source(file)
            blank.findAll(text).map { "${file.name}:${lineOf(text, it.range.first)}" }.toList()
        }
        assertTrue(
            "Info buttons with an empty explanation (${offenders.size}):\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `permission and pairing status lines stay inline`() {
        // Status, not explanation (Global Constraints): whether a permission is still Required, whether pairing
        // is connected, and what stands in its way. Each must be printed on the row, never only behind (i).
        val visibleCallees = setOf("Text", "CvStatusPill", "DevModeStatusLine")
        val statusKeys = mapOf(
            "PermissionsScreen.kt" to listOf(
                "permissions_status_required", "permissions_status_granted",
                "permissions_status_adb_connected", "permissions_status_adb_not_connected",
                "permission_status_devmode_off_tap_build", "permission_wd_off", "permission_status_wd_on_enter_code",
            ),
            "ShizukuOfferCard.kt" to listOf("permissions_shizuku_needs_permission", "permissions_shizuku_active"),
        )
        val problems = mutableListOf<String>()
        for ((fileName, keys) in statusKeys) {
            // AdbPermissionCard may move into its own file; its status keys count there too.
            val files = listOf(File(screensDir, fileName)) + if (fileName == "PermissionsScreen.kt") {
                screensDir.listFiles { f -> f.extension == "kt" && f.name.startsWith("AdbPermission") }.orEmpty().toList()
            } else emptyList()
            val texts = files.map { it.name to source(it) }
            for (key in keys) {
                val uses = texts.flatMap { (name, text) ->
                    Regex("""R\.string\.$key\b""").findAll(text).map { m ->
                        val consumer = enclosingCallees(text, m.range.first).firstOrNull { it !in transparentCallees }
                        Triple(name, lineOf(text, m.range.first), consumer)
                    }.toList()
                }
                if (uses.isEmpty()) problems += "$fileName: $key is not shown at all"
                uses.filter { it.third !in visibleCallees }
                    .forEach { (name, line, consumer) -> problems += "$name:$line $key goes to $consumer, not a visible line" }
            }
        }
        assertTrue(
            "Status lines must stay on screen (${problems.size}):\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    // ---- scanning ----

    private val explanationSegment = Regex("""(^|_)(description|desc|body|hint|note|explanation|subtitle)(_|$)""")

    /**
     * Status or action-needed text that must stay visible (Global Constraints), keyed `File.kt:key` so the same
     * key used inline in another file still fails; each with its reason. The pairing hint box and the
     * Developer-options / Wireless-debugging instruction paragraphs are NOT here: their status is already shown
     * by permission_status_devmode_off_tap_build, permission_wd_off and the "Not connected" chip, so the paragraphs go behind (i).
     */
    private val allowedInline: Map<String, String> = mapOf(
        "PermissionsScreen.kt:permission_battery_oem_hint" to "Toast after a refused battery exemption: a transient status, not a card",
        "WizardScreen.kt:wizard_storage_subtitle" to "stepInfo(): every step subtitle (this key stands for all of " +
            "stepSubtitleRes) is returned into the header (i); the scan cannot follow a value returned from a function",
        // The Auto-record 'nothing will record' WarningCard and the Storage 'pick a folder' label carry no
        // explanation-segment key, so they need no entry.
    )

    /** Callees whose explanation arguments are shown behind (i) when LocalSettingsDescriptionsAsInfo is on. */
    private val infoAwareCallees = setOf(
        "SettingsInfoButton", "WithInfo", "SettingsToggleRow", "SettingsNavigationRow", "NavigationRow",
        "SettingsSection", "joinExplanations",
        // Onboarding's own rows whose explanation argument is drawn only as (i): wizard/WizardCards.kt
        // OptionCard, PermissionsScreen.kt PermissionRow.
        "OptionCard", "PermissionRow",
    )

    /** Calls that only wrap a string on its way to a real consumer. */
    private val transparentCallees = setOf(
        "stringResource", "getString", "when", "if", "joinToString", "map", "format", "listOf", "remember",
        "pluralStringResource",
    )

    private fun isExplanationKey(key: String) = explanationSegment.containsMatchIn(key)

    private fun lineOf(text: String, index: Int) = text.substring(0, index).count { it == '\n' } + 1

    /** Names (vals and `...Res` helpers) that stand for an explanation key, with that key. */
    private fun aliasesIn(text: String): Map<String, String> {
        val aliases = mutableMapOf<String, String>()
        Regex("""val\s+(\w+)\s*=\s*[^\n]*?R\.string\.(\w+)""").findAll(text).forEach {
            if (isExplanationKey(it.groupValues[2])) aliases[it.groupValues[1]] = it.groupValues[2]
        }
        Regex("""fun\s+(\w+)\([^)]*\)\s*:\s*Int\s*=""").findAll(text).forEach { m ->
            val body = text.substring(m.range.last, nextDeclaration(text, m.range.last))
            Regex("""R\.string\.(\w+)""").findAll(body).map { it.groupValues[1] }.firstOrNull(::isExplanationKey)
                ?.let { aliases[m.groupValues[1]] = it }
        }
        return aliases
    }

    private fun nextDeclaration(text: String, from: Int): Int =
        Regex("""\n\s*(@Composable|private |internal |/\*\*|fun )""").find(text, from + 1)?.range?.first ?: text.length

    /**
     * Every `R.string.key` / alias use, as (index, key). Declarations of aliases themselves are skipped:
     * a `val` or `Int` helper only names the key, the use site is what prints it.
     */
    private fun uses(text: String, aliases: Map<String, String>): List<Pair<Int, String>> {
        val found = mutableListOf<Pair<Int, String>>()
        val helperBodies = Regex("""fun\s+(\w+)\([^)]*\)\s*:\s*Int\s*=""").findAll(text)
            .map { it.range.last..nextDeclaration(text, it.range.last) }.toList()
        val valLines = Regex("""val\s+\w+\s*=[^\n]*R\.string\.\w+""").findAll(text).map { it.range }.toList()
        Regex("""R\.string\.(\w+)""").findAll(text).forEach { m ->
            val key = m.groupValues[1]
            val inDeclaration = helperBodies.any { m.range.first in it } || valLines.any { m.range.first in it }
            if (isExplanationKey(key) && !inDeclaration) found += m.range.first to key
        }
        aliases.forEach { (name, key) ->
            Regex("""stringResource\(\s*$name\b""").findAll(text).forEach { found += it.range.first to key }
            Regex("""\b(?:text|description|subtitle)\s*=\s*$name\b""").findAll(text).forEach { found += it.range.first to key }
        }
        return found
    }

    /** Callee names around [index], innermost first, found by walking back over unmatched `(`. */
    private fun enclosingCallees(text: String, index: Int): List<String> {
        val names = mutableListOf<String>()
        var depth = 0
        var i = index - 1
        while (i >= 0) {
            when (text[i]) {
                ')' -> depth++
                '(' -> if (depth > 0) depth-- else {
                    val before = text.substring(0, i).trimEnd()
                    names += Regex("""[\w.]+$""").find(before)?.value?.substringAfterLast('.').orEmpty()
                }
                '}' -> if (isFunctionBoundary(text, i)) return names
            }
            i--
        }
        return names
    }

    /** A closing brace at column 0 ends a top-level declaration: stop walking back there. */
    private fun isFunctionBoundary(text: String, i: Int) = i == 0 || text[i - 1] == '\n'

    private fun offendersIn(fileName: String, text: String, from: Int = 0): List<String> {
        val aliases = aliasesIn(text)
        return uses(text, aliases)
            .filter { (index, _) -> index >= from }
            .filter { (index, _) ->
                val consumer = enclosingCallees(text, index).firstOrNull { it !in transparentCallees }
                consumer == null || consumer !in infoAwareCallees
            }
            .filter { (_, key) -> "$fileName:$key" !in allowedInline }
            .map { (index, key) -> "$fileName:${lineOf(text, index)} $key" }
            .distinct()
            .sorted()
    }
}
