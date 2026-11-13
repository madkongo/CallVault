/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/**
 * Names who asked for a recorder bring-up, from the stack of the call: the first frame that is not the bring-up
 * machinery itself, as `Class.method` (BabApps #95). Cheap, and only used when a bring-up is requested.
 */
object RecorderCaller {

    private val PLUMBING = listOf(
        "com.baba.callvault.server.RecorderCaller",
        "com.baba.callvault.server.RecorderBackend",
        "com.baba.callvault.server.RecorderServerLauncher",
        "com.baba.callvault.server.SingleFlight",
        "java.lang.Thread",
        "java.lang.Throwable",
        "kotlin.",
        "kotlinx.coroutines.",
        "java.util.concurrent.",
    )

    fun of(frames: Array<StackTraceElement>): String {
        val frame = frames.firstOrNull { f -> PLUMBING.none { f.className.startsWith(it) } } ?: return "unknown"
        return frame.className.substringAfterLast('.') + "." + frame.methodName
    }
}
