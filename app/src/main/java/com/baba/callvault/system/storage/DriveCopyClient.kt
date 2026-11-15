/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app's end of the Drive copier process ([DriveCopyService]): one request at a time, each with a time
 * limit, and each abandoned within about half a second when "Stop for now" is pressed.
 *
 * An abandoned, stuck or dead call all end the same way: [close] lets go of the service, which ends the
 * copier process, and the next call starts a fresh one. That is the whole recovery.
 */
class DriveCopyClient(context: Context, private val honorStop: Boolean = true) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var service: Messenger? = null
    @Volatile private var connection: ServiceConnection? = null
    @Volatile private var connected = CompletableDeferred<Boolean>()
    @Volatile private var pending: CompletableDeferred<Bundle?>? = null

    /** How a call ended. Only [Reply] carries data. */
    sealed interface Outcome {
        class Reply(val data: Bundle) : Outcome
        /** The copier process went away mid-call (Drive's own process dying takes it down). */
        data object Died : Outcome
        /** No answer within the limit; the copier was let go. */
        data object TimedOut : Outcome
        /** "Stop for now" was pressed; the copier was let go. */
        data object Stopped : Outcome
    }

    private val replies = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            pending?.complete(msg.data)
        }
    })

    private fun bind(): Boolean {
        close()
        connected = CompletableDeferred()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = Messenger(binder)
                connected.complete(true)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
                pending?.complete(null)
            }

            override fun onBindingDied(name: ComponentName) {
                service = null
                pending?.complete(null)
                connected.complete(false)
            }

            override fun onNullBinding(name: ComponentName) {
                connected.complete(false)
            }
        }
        connection = conn
        return runCatching {
            appContext.bindService(Intent(appContext, DriveCopyService::class.java), conn, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
    }

    /** Sends [what] with [data] and waits at most [timeoutMs] for the answer. */
    suspend fun call(what: Int, data: Bundle, timeoutMs: Long): Outcome {
        if (service == null) {
            if (!bind()) return Outcome.Died
            val up = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() }
            if (up != true) {
                close()
                return Outcome.Died
            }
        }
        val done = CompletableDeferred<Bundle?>()
        pending = done
        val sent = runCatching {
            service?.send(Message.obtain(null, what).also { it.data = data; it.replyTo = replies })
        }.isSuccess && service != null
        if (!sent) {
            close()
            return Outcome.Died
        }
        val start = SystemClock.elapsedRealtime()
        while (true) {
            withTimeoutOrNull(POLL_MS) { done.await() }
            if (done.isCompleted) {
                pending = null
                val reply = done.await()
                if (reply == null) {
                    close()
                    return Outcome.Died
                }
                return Outcome.Reply(reply)
            }
            if (honorStop && DriveCopyStop.isRequested()) {
                pending = null
                close()
                return Outcome.Stopped
            }
            if (SystemClock.elapsedRealtime() - start > timeoutMs) {
                pending = null
                close()
                return Outcome.TimedOut
            }
        }
    }

    /** Lets go of the copier, which ends its process. Safe to call twice. */
    fun close() {
        val conn = connection ?: return
        connection = null
        service = null
        runCatching { appContext.unbindService(conn) }
            .onFailure { AppLogger.d(TAG, "unbind: ${it.message}") }
    }

    companion object {
        private const val TAG = "CV:DriveCopyClient"
        private const val POLL_MS = 500L
        private const val CONNECT_TIMEOUT_MS = 15_000L
    }
}
