/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

// Fails the build when a locale has drifted from the base strings.
//
// Why this exists: translations rot silently. A feature adds strings to values/, ships, and every
// values-<locale>/ renders those strings in English inside an otherwise translated screen — nothing
// in the build says a word. On 2026-07-29 eight locales were found 47 strings behind, while the
// backlog still described the gap as three strings. Counting by hand is what failed; this task is
// the thing that counts instead.
//
// It checks three things:
//   missing     — the base defines a string the locale does not. Renders in English.
//   orphaned    — the locale defines a string the base does not. AGP's MissingDefaultResource lint
//                 fails the release build on these; see the fix/release-build-r8-translations branch.
//   placeholder — the locale's format specifiers differ from the base's. This one is not cosmetic:
//                 a dropped or renumbered %1$s throws IllegalFormatException at runtime, so the
//                 crash lands only on users reading that language.
//
// Escape hatch: -PallowMissingTranslations=true downgrades failure to a warning, for the case where
// a fix has to ship ahead of its translations. Deliberately explicit — nobody sets it by accident.
//
// Adding a locale: create an Android locale directory, such as `values-pt-rBR` for Brazilian
// Portuguese or `values-b+zh+Hant` for Traditional Chinese. That directory is the whole
// registration: AGP generates the locale config from the res folders, and SettingsScreen reads that
// generated config, so the in-app language picker lists it with no code change. Translate every key
// the base declares except those marked translatable="false" — including those, rather than skipping
// them, is what makes a locale "orphaned" here. `scripts/merge-translations.py` merges a fragment of
// new strings into the right files in sorted order; run this task before and after.

import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Resource elements that carry translatable text. Excludes styles, colors, dimens, and the rest. */
val translatableElements = setOf("string", "string-array", "plurals")

/** Matches legacy locale qualifiers and BCP 47 language, script, and region qualifiers. */
val localeDirPattern = Regex("""^values-(?:[a-z]{2}(?:-r[A-Z]{2})?|b\+[a-z]{2,3}(?:\+[A-Z][a-z]{3})?(?:\+(?:[A-Z]{2}|[0-9]{3}))?)$""")

/** A format specifier: `%1$s`, `%d`, `%%`. */
val placeholderPattern = Regex("""%(\d+\$[a-zA-Z]|[a-zA-Z%])""")

/**
 * Resource name to its text, for one `values*` directory.
 *
 * Parses the XML rather than matching `name="..."` textually, so a name inside a comment or an
 * attribute of some other element cannot be mistaken for a definition.
 */
fun resourcesIn(dir: File, translatableOnly: Boolean): Map<String, String> {
    if (!dir.isDirectory) return emptyMap()
    val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    return dir.listFiles { f: File -> f.extension == "xml" }
        .orEmpty()
        .flatMap { file ->
            val root = builder.parse(file).documentElement
            val children = root.childNodes
            (0 until children.length)
                .mapNotNull { children.item(it) as? Element }
                .filter { it.tagName in translatableElements }
                .filter { !translatableOnly || it.getAttribute("translatable") != "false" }
                .map { it.getAttribute("name") to it.textContent }
        }
        .toMap()
}

/**
 * The format specifiers a string uses, as a sorted SET.
 *
 * Distinct rather than a plain list because a `<plurals>` legitimately holds a different number of
 * items per language — English needs two forms, Polish and Russian need four — and this function
 * sees the concatenated text of all of them. Comparing lists would flag every correctly translated
 * plural as a mismatch and make proper pluralisation impossible to ship.
 *
 * The check that matters is unaffected: a locale that drops `%2$s`, or renumbers it, still differs
 * from the base and still fails. Only repetition stops being significant, and repeating a specifier
 * has never been what throws IllegalFormatException.
 */
fun placeholdersOf(text: String): List<String> =
    placeholderPattern.findAll(text).map { it.value }.distinct().sorted().toList()

val checkTranslations = tasks.register("checkTranslations") {
    group = "verification"
    description = "Fails when a locale is missing a base string, defines one the base does not, or changes its placeholders."

    val resDir = project.file("src/main/res")
    val lenient = project.hasProperty("allowMissingTranslations")

    doLast {
        val base = resourcesIn(File(resDir, "values"), translatableOnly = true)
        require(base.isNotEmpty()) { "No translatable strings found in $resDir/values — is the path right?" }

        val localeDirs = resDir.listFiles { f: File -> f.isDirectory && localeDirPattern.matches(f.name) }
            .orEmpty()
            .sortedBy { it.name }

        val problems = localeDirs.mapNotNull { dir ->
            val locale = resourcesIn(dir, translatableOnly = false)
            val missing = (base.keys - locale.keys).sorted()
            val orphaned = (locale.keys - base.keys).sorted()
            val mismatched = locale.keys.intersect(base.keys).sorted().mapNotNull { name ->
                val expected = placeholdersOf(base.getValue(name))
                val actual = placeholdersOf(locale.getValue(name))
                if (expected == actual) null else "$name (base ${expected}, locale ${actual})"
            }
            if (missing.isEmpty() && orphaned.isEmpty() && mismatched.isEmpty()) return@mapNotNull null

            buildString {
                appendLine("${dir.name} — ${missing.size} missing, ${orphaned.size} orphaned, ${mismatched.size} placeholder mismatches")
                missing.forEach { appendLine("    missing:     $it") }
                orphaned.forEach { appendLine("    orphaned:    $it") }
                mismatched.forEach { appendLine("    placeholder: $it") }
            }
        }

        if (problems.isEmpty()) {
            logger.lifecycle("Translations complete: ${localeDirs.size} locales, ${base.size} translatable strings each, placeholders consistent.")
            return@doLast
        }

        val report = buildString {
            appendLine("Translation coverage failed for ${problems.size} of ${localeDirs.size} locales.")
            appendLine()
            problems.forEach { append(it); appendLine() }
            appendLine("A missing string renders in English inside a translated screen.")
            appendLine("An orphaned string fails the release build via the MissingDefaultResource lint.")
            appendLine("A placeholder mismatch throws IllegalFormatException — a crash only that language sees.")
            append("To ship anyway: ./gradlew <task> -PallowMissingTranslations=true")
        }

        if (lenient) logger.warn(report) else throw GradleException(report)
    }
}

// Run with the rest of the verification suite, and gate every release: shipping is the moment an
// untranslated string reaches someone who cannot read it.
tasks.named("check") { dependsOn(checkTranslations) }
tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn(checkTranslations) }
