package com.baba.callvault.utils

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
 * This test scans every regex literal in the main sources (`Regex("…")`, `"…".toRegex()`, `Pattern.compile("…")`,
 * including `+`-joined pieces) and fails on such a brace. Inside a character class braces are literal in both
 * engines (`[{}]`), and `\p{…}`, `\x{…}`, `\N{…}` and `\Q…\E` are skipped. Escape a literal brace: `\\}` / `\}`.
 */
class RegexIcuBraceGuardTest {

    @Test
    fun noRegexLiteralInMainSourcesHasABraceIcuRejects() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val offenders = root.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
            .flatMap { file ->
                regexLiterals(file.readText())
                    .filter { (_, pattern) -> badBraceAt(pattern) != null }
                    .map { (line, pattern) -> "${file.relativeTo(root)}:$line: $pattern" }
            }
            .toList()
        assertTrue(
            "Regex literals with a brace Android's ICU rejects (escape it as \\\\} or \\\\{):\n" + offenders.joinToString("\n"),
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
            private val C = Regex(${"\"\"\""}\{day-?\}${'$'}{Other.PATTERN}${"\"\"\""})
            val d = "a{b".toRegex()
            val e = Regex(pattern.toString())
            val f = Regex("[^/]{1,${'$'}MAX_RUN}")
        """.trimIndent()
        val found = regexLiterals(source)
        assertEquals(listOf("(?:}|x)", "(?<!\\d)", "\\d{1,3}", "\\{day-?\\}1", "a{b", "[^/]{1,1}"), found.map { it.second })
        assertEquals(listOf(1, 3, 4, 6, 7, 9), found.map { it.first })
        assertEquals(listOf("(?:}|x)", "a{b"), found.map { it.second }.filter { badBraceAt(it) != null })
        assertFalse(found.any { it.second.contains("PATTERN") })
    }

    // --- scanner ---

    /** Every regex literal piece in [source], as (1-based line, pattern as the regex engine receives it). */
    private fun regexLiterals(source: String): List<Pair<Int, String>> {
        val found = mutableListOf<Pair<Int, String>>()
        Regex("""\b(?:Regex|Pattern\.compile)\(""").findAll(source).forEach { call ->
            var at = call.range.last + 1
            while (true) {
                at = skipSpaceAndComments(source, at)
                val literal = readLiteral(source, at) ?: break
                found += lineOf(source, at) to literal.first
                at = skipSpaceAndComments(source, literal.second)
                if (at < source.length && source[at] == '+') at++ else break
            }
        }
        Regex("""\.toRegex\(""").findAll(source).forEach { call ->
            literalEndingAt(source, call.range.first)?.let { found += lineOf(source, it.first) to it.second }
        }
        return found.sortedBy { it.first }
    }

    private fun lineOf(source: String, index: Int) = source.substring(0, index).count { it == '\n' } + 1

    private fun skipSpaceAndComments(s: String, from: Int): Int {
        var i = from
        while (i < s.length) {
            when {
                s[i].isWhitespace() -> i++
                s.startsWith("//", i) -> i = s.indexOf('\n', i).let { if (it < 0) s.length else it }
                else -> return i
            }
        }
        return i
    }

    /** The literal starting at [at] (pattern, index after it), or null when none starts there. */
    private fun readLiteral(s: String, at: Int): Pair<String, Int>? = when {
        s.startsWith("\"\"\"", at) -> {
            val end = s.indexOf("\"\"\"", at + 3)
            if (end < 0) null else stripTemplates(s.substring(at + 3, end)) to end + 3
        }
        at < s.length && s[at] == '"' -> {
            var i = at + 1
            while (i < s.length && s[i] != '"') i += if (s[i] == '\\') 2 else 1
            if (i >= s.length) null else unescape(stripTemplates(s.substring(at + 1, i))) to i + 1
        }
        else -> null
    }

    /** A `"…"` or `"""…"""` literal that ends right before [end] (start index, pattern), or null. */
    private fun literalEndingAt(s: String, end: Int): Pair<Int, String>? {
        if (end < 1 || s[end - 1] != '"') return null
        if (end >= 3 && s.startsWith("\"\"\"", end - 3)) {
            val start = s.lastIndexOf("\"\"\"", end - 4)
            return if (start < 0) null else start to stripTemplates(s.substring(start + 3, end - 3))
        }
        var start = end - 2
        while (start >= 0 && !(s[start] == '"' && (start == 0 || s[start - 1] != '\\'))) start--
        return if (start < 0) null else start to unescape(stripTemplates(s.substring(start + 1, end - 1)))
    }

    /**
     * `${…}` and `${'$'}name` templates are Kotlin, not regex: their braces never reach the engine. Each becomes `1`, so a
     * template used as a quantifier bound (`{1,${'$'}MAX}`) still reads as one. What a template holds is not checked.
     */
    private fun stripTemplates(literal: String) =
        literal.replace(Regex("""\$(?:\{[^}]*\}|[A-Za-z_][A-Za-z0-9_]*)"""), "1")

    /** A Kotlin `"…"` body to the string it holds: `\\` is one backslash, `\"` a quote. */
    private fun unescape(body: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < body.length) {
            if (body[i] == '\\' && i + 1 < body.length) {
                out.append(
                    when (val c = body[i + 1]) {
                        'n' -> '\n'
                        't' -> '\t'
                        'r' -> '\r'
                        else -> c
                    },
                )
                i += 2
            } else {
                out.append(body[i++])
            }
        }
        return out.toString()
    }

    /** Index of the first brace in [pattern] that ICU rejects, or null when there is none. */
    private fun badBraceAt(pattern: String): Int? {
        var i = 0
        var classDepth = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> i = afterEscape(pattern, i)
                classDepth > 0 -> {
                    if (c == '[') classDepth++ else if (c == ']') classDepth--
                    i++
                }
                c == '[' -> {
                    classDepth = 1
                    i++
                    // A `]` or `^]` right after the opening bracket is a literal, not the end.
                    if (pattern.startsWith("^", i)) i++
                    if (pattern.startsWith("]", i)) i++
                }
                c == '}' -> return i
                c == '{' -> i = QUANTIFIER.matchAt(pattern, i)?.range?.last?.plus(1) ?: return i
                else -> i++
            }
        }
        return null
    }

    /** Index after the escape starting at [at]: `\p{…}`-style escapes and `\Q…\E` are skipped whole. */
    private fun afterEscape(pattern: String, at: Int): Int {
        val next = pattern.getOrNull(at + 1) ?: return at + 1
        if (next == 'Q') return pattern.indexOf("\\E", at + 2).let { if (it < 0) pattern.length else it + 2 }
        if (next in "pPxN" && pattern.getOrNull(at + 2) == '{') {
            return pattern.indexOf('}', at + 3).let { if (it < 0) pattern.length else it + 1 }
        }
        return at + 2
    }

    private companion object {
        val QUANTIFIER = Regex("""\{\d+(?:,\d*)?\}""")
    }
}
