package com.baba.callvault.ui.viewmodels

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Kotlin runs property initializers and `init` blocks in text order. A ViewModel's init starts real work
 * (callbacks, refreshes), so a property declared below it is either still null when that work touches it, or
 * re-initialised afterwards. The first crashed Home on every open (C24 Wi-Fi flow, OP9, 2026-11-19); the second
 * reset `watchingCatalog` and `listJob` after refresh() had set them. Declare a ViewModel's properties above init.
 */
class ViewModelInitOrderGuardTest {

    private val property = Regex("""^    (@\w+(\([^)]*\))?\s+)*(private |internal |protected |public )?(override )?(val|var|lateinit var) """)

    @Test
    fun noViewModelDeclaresAPropertyBelowItsInitBlock() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.name.endsWith("ViewModel.kt") }
            .flatMap { file ->
                val lines = file.readLines()
                // The first init block: anything declared after it is initialised after its work has started.
                val init = lines.indexOfFirst { it.startsWith("    init {") }
                if (init < 0) return@flatMap emptySequence()
                val companion = lines.indexOfFirst { it.startsWith("    companion object") }.let { if (it < 0) lines.size else it }
                lines.withIndex()
                    .filter { (i, line) -> i in (init + 1) until companion && property.containsMatchIn(line) }
                    .map { (i, line) -> "${file.name}:${i + 1}: ${line.trim()}" }
                    .asSequence()
            }
            .toList()
        assertTrue("Properties declared below init (move them above it):\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
