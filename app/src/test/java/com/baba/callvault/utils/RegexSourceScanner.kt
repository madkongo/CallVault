package com.baba.callvault.utils

/**
 * Finds the regexes in Kotlin sources, as the engine will receive them, for [RegexIcuBraceGuardTest]. Reads text, not
 * compiled code, so it is deterministic and needs no class to load.
 *
 * Finds: literals passed to `Regex(…)` / `Pattern.compile(…)` (pieces joined by `+` included) and `"…".toRegex()`;
 * an UPPER_CASE string constant passed by name (`Regex(SEP)`, `Regex(Holder.SEP, option)`); and every UPPER_CASE
 * string constant whose name says it is a pattern ([PATTERN_NAME]), used or not.
 *
 * Templates are resolved: `$NAME` or `${Owner.NAME}` becomes the UPPER_CASE constant `NAME` (string or number) from the
 * same file if it has one, else from the first file by path that does. Anything else (a local, a call) becomes `1`,
 * so it still reads as a quantifier bound. Constants that refer to themselves stop resolving instead of looping.
 */
internal class RegexSourceScanner(private val sources: Map<String, String>) {

    data class Found(val path: String, val line: Int, val pattern: String)

    private data class Piece(val body: String, val raw: Boolean)

    /** A constant: either string [pieces] or a [number]. */
    private data class Def(val path: String, val line: Int, val pieces: List<Piece>?, val number: String?)

    private val lineStarts: Map<String, IntArray> = sources.mapValues { (_, text) ->
        (listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }).toIntArray()
    }

    private val defs: Map<String, List<Def>> = sources.entries.sortedBy { it.key }.flatMap { (path, text) ->
        DEF.findAll(text).mapNotNull { m ->
            val at = m.range.last + 1
            val chain = readChain(text, at)
            val line = lineOf(path, m.range.first)
            when {
                chain.first.isNotEmpty() -> m.groupValues[1] to Def(path, line, chain.first, null)
                else -> NUMBER.matchAt(text, at)?.let { m.groupValues[1] to Def(path, line, null, it.groupValues[1]) }
            }
        }.toList()
    }.groupBy({ it.first }, { it.second })

    fun patterns(): List<Found> {
        val found = mutableListOf<Found>()
        sources.forEach { (path, text) ->
            CALL.findAll(text).forEach { call ->
                val at = call.range.last + 1
                val (pieces, _) = readChain(text, at)
                if (pieces.isNotEmpty()) {
                    // One entry per piece, so a report points at the line holding the brace.
                    var from = at
                    pieces.forEach { piece ->
                        val start = text.indexOf(piece.body, from).coerceAtLeast(at)
                        found += Found(path, lineOf(path, start), resolve(listOf(piece), path, emptySet()))
                        from = start + piece.body.length
                    }
                } else {
                    BY_NAME.matchAt(text, skipSpaceAndComments(text, at))?.let { m ->
                        val def = lookup(m.groupValues[1].substringAfterLast('.'), path)
                        if (def?.pieces != null) {
                            found += Found(path, lineOf(path, call.range.first), resolve(def.pieces, def.path, emptySet()))
                        }
                    }
                }
            }
            TO_REGEX.findAll(text).forEach { call ->
                literalEndingAt(text, call.range.first)?.let { (start, piece) ->
                    found += Found(path, lineOf(path, start), resolve(listOf(piece), path, emptySet()))
                }
            }
        }
        defs.forEach { (name, list) ->
            if (!PATTERN_NAME.containsMatchIn(name)) return@forEach
            list.filter { it.pieces != null }.forEach { def ->
                found += Found(def.path, def.line, resolve(def.pieces!!, def.path, setOf(name)))
            }
        }
        return found.distinct().sortedWith(compareBy({ it.path }, { it.line }))
    }

    private fun lineOf(path: String, index: Int): Int {
        val starts = lineStarts.getValue(path)
        val i = starts.binarySearch(index)
        return (if (i >= 0) i else -i - 2) + 1
    }

    /** The definition of [name], from [path] first. */
    private fun lookup(name: String, path: String): Def? {
        val list = defs[name] ?: return null
        return list.firstOrNull { it.path == path } ?: list.first()
    }

    private fun resolve(pieces: List<Piece>, path: String, stack: Set<String>): String =
        pieces.joinToString("") { evaluate(it, path, stack) }

    /** A literal's body as the string Kotlin builds: escapes undone (not in raw strings), templates filled in. */
    private fun evaluate(piece: Piece, path: String, stack: Set<String>): String {
        val body = piece.body
        val out = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                !piece.raw && c == '\\' && i + 1 < body.length -> {
                    val next = body[i + 1]
                    if (next == 'u' && i + 6 <= body.length) {
                        out.append(body.substring(i + 2, i + 6).toInt(16).toChar()); i += 6
                    } else {
                        out.append(ESCAPES[next] ?: next); i += 2
                    }
                }
                c == '$' && body.getOrNull(i + 1) == '{' -> {
                    val end = closingBrace(body, i + 1)
                    out.append(templateValue(body.substring(i + 2, end).trim(), path, stack))
                    i = end + 1
                }
                c == '$' && body.getOrNull(i + 1)?.let { it.isLetter() || it == '_' } == true -> {
                    var end = i + 1
                    while (end < body.length && (body[end].isLetterOrDigit() || body[end] == '_')) end++
                    out.append(templateValue(body.substring(i + 1, end), path, stack))
                    i = end
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private fun closingBrace(s: String, open: Int): Int {
        var depth = 0
        for (i in open until s.length) {
            if (s[i] == '{') depth++ else if (s[i] == '}' && --depth == 0) return i
        }
        return s.length - 1
    }

    private fun templateValue(expression: String, path: String, stack: Set<String>): String {
        if (expression == "'$'") return "$"
        val name = expression.substringAfterLast('.')
        if (!IDENTIFIER.matches(name) || name in stack) return UNKNOWN
        val def = lookup(name, path) ?: return UNKNOWN
        def.number?.let { return it }
        return resolve(def.pieces!!, def.path, stack + name)
    }

    companion object {
        private val CALL = Regex("""\b(?:Regex|Pattern\.compile)\(""")
        private val TO_REGEX = Regex("""\.toRegex\(""")
        private val DEF = Regex("""\b(?:const\s+)?val\s+([A-Z][A-Z0-9_]*)\s*(?::\s*[A-Za-z.?]+\s*)?=\s*""")
        private val NUMBER = Regex("""(-?\d+)L?\b""")
        private val BY_NAME = Regex("""([A-Za-z_][A-Za-z0-9_.]*)\s*[,)]""")
        private val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

        /** Constant names that say they hold a pattern. */
        private val PATTERN_NAME = Regex("""PATTERN|REGEX|(?:^|_)RX(?:_|$)""")
        private const val UNKNOWN = "1"
        private val ESCAPES = mapOf('n' to '\n', 't' to '\t', 'r' to '\r', 'b' to '\b')

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

        /** `"…" + """…""" + …` starting at [at] (comments allowed between), and the index after it. */
        private fun readChain(s: String, at: Int): Pair<List<Piece>, Int> {
            val pieces = mutableListOf<Piece>()
            var i = at
            while (true) {
                i = skipSpaceAndComments(s, i)
                val (piece, end) = readLiteral(s, i) ?: break
                pieces += piece
                i = skipSpaceAndComments(s, end)
                if (i < s.length && s[i] == '+') i++ else { i = end; break }
            }
            return pieces to i
        }

        private fun readLiteral(s: String, at: Int): Pair<Piece, Int>? = when {
            s.startsWith("\"\"\"", at) -> {
                val end = s.indexOf("\"\"\"", at + 3)
                if (end < 0) null else Piece(s.substring(at + 3, end), raw = true) to end + 3
            }
            at < s.length && s[at] == '"' -> {
                var i = at + 1
                while (i < s.length && s[i] != '"') i += if (s[i] == '\\') 2 else 1
                if (i >= s.length) null else Piece(s.substring(at + 1, i), raw = false) to i + 1
            }
            else -> null
        }

        /** A literal that ends right before [end]: (start index, piece), or null. */
        private fun literalEndingAt(s: String, end: Int): Pair<Int, Piece>? {
            if (end < 1 || s[end - 1] != '"') return null
            if (end >= 3 && s.startsWith("\"\"\"", end - 3)) {
                val start = s.lastIndexOf("\"\"\"", end - 4)
                return if (start < 0) null else start to Piece(s.substring(start + 3, end - 3), raw = true)
            }
            var start = end - 2
            while (start >= 0 && !(s[start] == '"' && (start == 0 || s[start - 1] != '\\'))) start--
            return if (start < 0) null else start to Piece(s.substring(start + 1, end - 1), raw = false)
        }

        /** Index of the first brace in [pattern] that ICU rejects, or null when there is none. */
        fun badBraceAt(pattern: String): Int? {
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

        private val QUANTIFIER = Regex("""\{\d+(?:,\d*)?\}""")
    }
}
