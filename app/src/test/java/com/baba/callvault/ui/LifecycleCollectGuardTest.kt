package com.baba.callvault.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F5: UI code must collect flows with collectAsStateWithLifecycle, which stops collecting while the app is in the
 * background. Plain collectAsState keeps Home's catalog, waveform and contact flows running with the screen off.
 */
class LifecycleCollectGuardTest {

    private val root: File = listOf(File("src/main/java/com/baba/callvault"), File("app/src/main/java/com/baba/callvault"))
        .first { it.isDirectory }

    @Test
    fun `no UI file calls plain collectAsState`() {
        val plain = Regex("""(?<![\w])collectAsState\s*[(\n]|import androidx\.compose\.runtime\.collectAsState\b""")
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                file.readLines().any { line ->
                    val code = line.substringBefore("//").trim()
                    !code.startsWith("*") && !code.startsWith("/*") && plain.containsMatchIn(code)
                }
            }
            .map { it.relativeTo(root).path }.toList()
        assertTrue("Use collectAsStateWithLifecycle instead of collectAsState in: $offenders", offenders.isEmpty())
    }
}
