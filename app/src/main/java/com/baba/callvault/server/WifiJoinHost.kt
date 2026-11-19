/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

/**
 * The recorder process's one Wi-Fi join watch (review N15), made on the first arm so a recorder that is never asked
 * starts no thread. Joins, checks and pushes to the app all run on its own thread, never on a binder thread.
 */
internal class WifiJoinHost(private val authority: String) {

    private var monitor: WifiJoinMonitor? = null
    private var queue: WifiJoinEventQueue? = null

    @Synchronized
    fun arm(on: Boolean, knownKeys: Set<String>, salt: String): Boolean {
        if (!on && monitor == null) return false
        return (monitor ?: create()).arm(on, knownKeys, salt)
    }

    @Synchronized
    fun drain(): Array<String> = queue?.drain() ?: emptyArray()

    private fun create(): WifiJoinMonitor {
        val handler = Handler(HandlerThread(THREAD).apply { start() }.looper)
        val events = WifiJoinEventQueue(
            push = { BinderDelivery.deliverWifiJoinEventsToApp(authority, it) },
            post = { task -> handler.post { task() } },
        )
        val created = WifiJoinMonitor(
            watch = ShellWifiNetworkWatch(handler),
            runner = HandlerDelayedRunner(handler),
            approve = { keys, salt -> WirelessDebuggingApproval.keptSwitchApprover().run(keys, salt) },
            emit = events::add,
        )
        queue = events
        monitor = created
        return created
    }

    /** One pending task on [handler]; scheduling again replaces it. */
    private class HandlerDelayedRunner(private val handler: Handler) : DelayedRunner {
        private val token = Any()

        override fun runAfter(delayMs: Long, task: () -> Unit) {
            handler.removeCallbacksAndMessages(token)
            handler.postAtTime({ task() }, token, SystemClock.uptimeMillis() + delayMs)
        }

        override fun cancelPending() = handler.removeCallbacksAndMessages(token)
    }

    private companion object {
        const val THREAD = "recorder-wifi-join"
    }
}
