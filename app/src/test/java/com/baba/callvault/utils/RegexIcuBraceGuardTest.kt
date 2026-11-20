package com.baba.callvault.utils

import com.baba.callvault.utils.RegexSourceScanner.Companion.badBraceAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android compiles regexes with ICU, not the JVM's engine, and ICU is stricter about braces: a bare `}` or a `{`
 * that does not open a `{n}`, `{n,}` or `{n,m}` quantifier is a syntax error there, while the desktop JVM (where the
 * unit tests run) reads it as a literal. `(?:}|[A-Za-z_]+=)` passed every test here and threw PatternSyntaxException
 * on a OnePlus 9 Pro, Android 14 (rf5, 2026-11-20): it sat in an object's initializer, so the whole
 * WirelessDebuggingApproval object failed to load in the recorder and both Wi-Fi approval paths broke.
 *
 * This test checks, in the main sources, every regex literal (`Regex("…")`, `"…".toRegex()`, `Pattern.compile("…")`,
 * `+`-joined pieces included), every string constant passed to one by name (`Regex(PATTERN)`), and every string
 * constant whose name says it is a pattern (`…PATTERN…`, `…REGEX…`, `…RX…`). Templates (`$JOINER`,
 * `${FileNameStamp.PAIR_PATTERN}`) are resolved to the constant they name, same file first; one that names no
 * constant reads as `1`. See [RegexSourceScanner]. Escape a literal brace: `\\}` / `\}`.
 */
class RegexIcuBraceGuardTest {

    @Test
    fun noRegexInMainSourcesHasABraceIcuRejects() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val sources = root.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
            .sortedBy { it.path }
            .associate { it.relativeTo(root).path to it.readText() }
        val found = RegexSourceScanner(sources).patterns()
        val offenders = found.filter { badBraceAt(it.pattern) != null }.map { "${it.path}:${it.line}: ${it.pattern}" }
        assertTrue("the scanner found almost nothing; is it still reading the sources? (${found.size})", found.size > 50)
        assertTrue(
            "Regexes with a brace Android's ICU rejects (escape it as \\\\} or \\\\{):\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun scannerRejectsTheBraceThatBrokeTheRecorder() {
        assertEquals(14, badBraceAt("(?m)^[ \\t]*(?:}|[A-Za-z_]+=)"))
        assertTrue(badBraceAt("a{b") != null)
        assertTrue(badBraceAt("x{1,a}") != null)
        assertTrue(badBraceAt("{") != null)
    }

    @Test
    fun scannerAcceptsEscapedBracesQuantifiersClassesAndProperties() {
        assertEquals(null, badBraceAt("(?m)^[ \\t]*(?:\\}|[A-Za-z_]+=)"))
        assertEquals(null, badBraceAt("\\d{8}_\\d{6}(?:x){5}a{2,}b{1,3}"))
        assertEquals(null, badBraceAt("[\\^$.|?*+()\\[\\]{}]"))
        assertEquals(null, badBraceAt("\\{[^{}]*\\}"))
        assertEquals(null, badBraceAt("[^\\p{L}\\p{N}]+\\p{Mn}\\x{41}"))
        assertEquals(null, badBraceAt("\\Qa{b}\\E\\d{2}"))
    }

    @Test
    fun scannerFindsLiteralsInKotlinSource() {
        val source = """
            private val A = Regex("(?:}|x)")
            private val B = Regex(
                "(?<!\\d)" +   // comment
                    "\\d{1,3}"
            )
            private val C = Regex(${Q}\{day-?\}${D}{Other.UNKNOWN}${Q})
            val d = "a{b".toRegex()
            val e = Regex(pattern.toString())
            val f = Regex("[^/]{1,${D}MAX_RUN}")
        """.trimIndent()
        val found = scan(source)
        assertEquals(listOf("(?:}|x)", "(?<!\\d)", "\\d{1,3}", "\\{day-?\\}1", "a{b", "[^/]{1,1}"), found.map { it.pattern })
        assertEquals(listOf(1, 3, 4, 6, 7, 9), found.map { it.line })
        assertEquals(listOf("(?:}|x)", "a{b"), found.map { it.pattern }.filter { badBraceAt(it) != null })
    }

    @Test
    fun templatesResolveToTheConstantTheyName() {
        val stamp = """
            object FileNameStamp {
                const val DAY_PATTERN = ${Q}(?:\d{8})${Q}
                private const val JOINER = ${Q}(?:}|_)${Q}
                const val PAIR_PATTERN = "${D}DAY_PATTERN${D}JOINER"
                private val INSIDE = Regex(${Q}(?<!\d)${D}JOINER(?!\d)${Q})
            }
        """.trimIndent()
        val log = """
            private const val MAX_RUN = 60
            private val STAMP = Regex(${Q}x${D}{FileNameStamp.PAIR_PATTERN}y{1,${D}MAX_RUN}${Q})
        """.trimIndent()
        val found = RegexSourceScanner(mapOf("FileNameStamp.kt" to stamp, "CallEndLog.kt" to log)).patterns()

        val inside = found.single { it.path == "FileNameStamp.kt" && it.line == 5 }
        assertEquals("(?<!\\d)(?:}|_)(?!\\d)", inside.pattern)
        assertTrue(badBraceAt(inside.pattern) != null)
        val stampRx = found.single { it.path == "CallEndLog.kt" }
        assertEquals("x(?:\\d{8})(?:}|_)y{1,60}", stampRx.pattern)
        assertTrue(badBraceAt(stampRx.pattern) != null)
    }

    @Test
    fun aConstantNamedLikeAPatternIsCheckedEvenUnused() {
        val found = scan(
            """
            const val DAY_PATTERN = "a}b"
            private val PHONE_REGEX_PART = "(?:x)"
            private const val LABEL = "{not a pattern}"
            """.trimIndent(),
        )
        assertEquals(listOf("a}b", "(?:x)"), found.map { it.pattern })
        assertEquals(listOf(1, 2), found.map { it.line })
    }

    @Test
    fun aConstantPassedToRegexByNameIsChecked() {
        val found = scan(
            """
            private const val SEP = "(?:}|;)"
            private val SPLIT = Regex(SEP)
            private val OTHER = Regex(Holder.SEP, RegexOption.IGNORE_CASE)
            """.trimIndent(),
        )
        assertEquals(listOf("(?:}|;)", "(?:}|;)"), found.map { it.pattern })
        assertEquals(listOf(2, 3), found.map { it.line })
    }

    @Test
    fun aTemplateResolvesToItsOwnFileFirst() {
        val a = "private const val J = \"_\"\nval X = Regex(\"a${D}J\")"
        val b = "private const val J = \"}\""
        val found = RegexSourceScanner(mapOf("a.kt" to a, "b.kt" to b)).patterns()
        assertEquals(listOf("a_"), found.map { it.pattern })
    }

    @Test
    fun aConstantThatRefersToItselfDoesNotLoop() {
        val found = scan("const val A_PATTERN = \"x${D}A_PATTERN\"")
        assertFalse(found.isEmpty())
    }

    private fun scan(source: String) = RegexSourceScanner(mapOf("A.kt" to source)).patterns()

    private companion object {
        const val Q = "\"\"\""
        const val D = "$"
    }
}
