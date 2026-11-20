/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.utils

import java.util.Collections
import java.util.IdentityHashMap

/**
 * A failure as one log line, with what caused it. A wrapper's own text says nothing: a class whose static
 * initializer threw reads "ExceptionInInitializerError: null" (rf5, 2026-11-20, a regex Android rejected), and a
 * reflective call reads "InvocationTargetException: null". The causes name the real exception.
 */
object FailureChain {

    /** Links shown at most; a deeper chain is cut. */
    const val MAX_LINKS = 4
    private const val MAX_MESSAGE = 160
    private const val JOIN = ", caused by "
    private val WHITESPACE = Regex("\\s+")

    /** `Class: message, caused by Class: message, …`, one line, never a stack. */
    fun describe(failure: Throwable): String {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val links = mutableListOf<String>()
        var current: Throwable? = failure
        while (current != null && links.size < MAX_LINKS && seen.add(current)) {
            links += link(current)
            current = current.cause
        }
        return links.joinToString(JOIN)
    }

    private fun link(failure: Throwable): String {
        val message = failure.message?.replace(WHITESPACE, " ")?.trim()?.take(MAX_MESSAGE)
        return failure.javaClass.simpleName + if (message.isNullOrEmpty()) "" else ": $message"
    }
}
