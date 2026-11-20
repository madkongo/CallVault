/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A listener or callback type that Android 11 (API 30, our minSdk) does not have may only be created inside a class marked
 * `@RequiresApi`, which only Android 12+ code paths reach.
 *
 * Created anywhere else, the object is one SDK check away from Android 11: a field initializer is no check at all, and
 * `VoipCallDetector` and `WifiCallRouteWatcher` each made an `AudioManager.OnModeChangedListener` in one. On Android 11
 * the constructor threw NoClassDefFoundError; the keep-alive builds the detector on every start, so 2.5.1 crash-looped
 * there as soon as the recorder was set up (Android 11 emulator, 2026-11-20). [ModeChangeSubscription] is the gated
 * home for that listener now.
 *
 * The list holds the post-API-30 framework types this app creates or could reach for; add one when you first use it.
 */
class NewerApiListenersStayInGatedClassesTest {

    @Test
    fun `newer-API listeners are only created in RequiresApi classes`() {
        val sourceDir = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }
        val offenders = sourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> ungatedCreations(file.readLines()).map { line -> "${file.name}:$line" } }
            .toList()
        assertTrue(
            "Create these only inside a @RequiresApi class (see ModeChangeSubscription), never in a field initializer " +
                "or an ungated class; Android 11 cannot load them: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the scan flags a field initializer and passes a gated class`() {
        val ungated = """
            class Detector(context: Context) {
                private val modeListener = AudioManager.OnModeChangedListener { mode ->
                    evaluate(mode)
                }
            }
        """.trimIndent().lines()
        val gated = """
            @RequiresApi(Build.VERSION_CODES.S)
            internal class Subscription(am: AudioManager) {
                private val listener = AudioManager.OnModeChangedListener { }
            }
        """.trimIndent().lines()
        val subclass = """
            class Service {
                private inner class Callback : TelephonyCallback(), TelephonyCallback.CallStateListener {
                }
            }
        """.trimIndent().lines()

        assertEquals(listOf(2), ungatedCreations(ungated))
        assertEquals(emptyList<Int>(), ungatedCreations(gated))
        assertEquals(listOf(2), ungatedCreations(subclass))
    }

    @Test
    fun `the scan finds a newer-API type anywhere in a supertype list`() {
        val anonymous = """
            class Watcher {
                private val callback = object : AudioManager.AudioPlaybackCallback(), AudioManager.OnModeChangedListener {
                }
            }
        """.trimIndent().lines()
        val secondInterface = """
            class Service {
                private inner class Callback : Base(), TelephonyCallback.CallStateListener {
                }
            }
        """.trimIndent().lines()
        val interfaceOnly = """
            class Holder {
                private inner class Listener(private val am: AudioManager) : OnModeChangedListener {
                }
            }
        """.trimIndent().lines()
        val notACreation = """
            class Holder {
                fun register(executor: Executor, listener: AudioManager.OnModeChangedListener) = Unit
                fun current(): TelephonyCallback? = null
            }
        """.trimIndent().lines()

        assertEquals(listOf(2), ungatedCreations(anonymous))
        assertEquals(listOf(2), ungatedCreations(secondInterface))
        assertEquals(listOf(2), ungatedCreations(interfaceOnly))
        assertEquals(emptyList<Int>(), ungatedCreations(notACreation))
    }

    /** 1-based line numbers where a newer-API type is created outside a `@RequiresApi` class. */
    private fun ungatedCreations(lines: List<String>): List<Int> = lines.indices.filter { i ->
        val code = lines[i].trimStart()
        val isComment = code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
        !isComment && createsNewerApiType(lines[i]) && !isGated(lines, enclosingDeclaration(lines, i))
    }.map { it + 1 }

    /** A SAM lambda (`Type {`), or the type anywhere in the supertype list of an `object :` or a class declaration. */
    private fun createsNewerApiType(line: String): Boolean {
        if (samLambda.containsMatchIn(line)) return true
        if (line.contains(Regex("""\bfun\s"""))) return false
        val supertypes = supertypeList.find(line)?.groupValues?.get(1) ?: return false
        return newerType.containsMatchIn(supertypes)
    }

    /** The innermost class or object declared at, or around, [index]; -1 for top-level code. */
    private fun enclosingDeclaration(lines: List<String>, index: Int): Int {
        if (declaration.containsMatchIn(lines[index])) return index
        val indent = indentOf(lines[index])
        return (index - 1 downTo 0).firstOrNull { j ->
            lines[j].isNotBlank() && indentOf(lines[j]) < indent && declaration.containsMatchIn(lines[j])
        } ?: -1
    }

    /** True when the annotations right above the declaration include `@RequiresApi`. */
    private fun isGated(lines: List<String>, declarationIndex: Int): Boolean {
        if (declarationIndex < 0) return false
        if (lines[declarationIndex].contains("@RequiresApi")) return true
        return (declarationIndex - 1 downTo 0)
            .asSequence()
            .map { lines[it].trim() }
            .takeWhile { it.startsWith("@") }
            .any { it.startsWith("@RequiresApi") }
    }

    private fun indentOf(line: String) = line.length - line.trimStart().length

    private companion object {
        /** Framework listener and callback types added after API 30, as the source names them. */
        val NEWER_API_TYPES = listOf(
            "OnModeChangedListener", // AudioManager, API 31
            "OnCommunicationDeviceChangedListener", // AudioManager, API 31
            "OnPreferredMixerAttributesChangedListener", // AudioManager, API 34
            "TelephonyCallback", // API 31
            "OnReceiveContentListener", // View, API 31
            "ViewTranslationCallback", // View, API 31
            "OnBackInvokedCallback", // API 33
            "OnBackAnimationCallback", // API 34
        )
        private val names = NEWER_API_TYPES.joinToString("|")

        val samLambda = Regex("""\b(?:$names)\s*\{""")

        /** One of the types, bare or qualified (`AudioManager.OnModeChangedListener`, `TelephonyCallback.CallStateListener`). */
        val newerType = Regex("""\b(?:$names)\b""")

        /** What follows the colon of `object :`, of `class Name :` or of a primary constructor's `) :`. */
        val supertypeList = Regex("""(?:\bobject\s*(?:\w+\s*)?|\b(?:class|interface)\s+\w+(?:<[^>]*>)?\s*|\)\s*):\s*(.*)""")

        val declaration = Regex("""^\s*(?:[a-z]+\s+)*(?:class|object|interface)\s+\w+""")
    }
}
