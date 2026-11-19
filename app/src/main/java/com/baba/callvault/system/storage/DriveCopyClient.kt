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

/** Something the Drive copy can be asked to do: [DriveCopyClient] (the `:drive` process) or [LocalDriveCopier]. */
interface DriveCopier {
    /** Short name for the journal: where the work runs. */
    val where: String

    /** Sends [what] with [data] and waits at most [timeoutMs] for the answer. */
    suspend fun call(what: Int, data: Bundle, timeoutMs: Long): DriveCopyClient.Outcome

    /** Lets go of whatever the copier holds. Safe to call twice. */
    fun close()
}

/**
 * The app's end of the Drive copier process ([DriveCopyService]): one request at a time, each with a time
 * limit, and each abandoned within about half a second when "Stop for now" is pressed.
 *
 * An abandoned, stuck or dead call all end the same way: [close] lets go of the service, and the next call
 * binds again. That ends the copier process only when no other client (the sweep and the after-call copy each
 * have one) is still bound, so a reply to an abandoned call can still arrive later. Every call therefore
 * carries its own sequence number in `arg1` and its own reply Messenger ([ReplyHandler]): a late reply can
 * only reach the abandoned call it belongs to, and is dropped and journalled (review C15).
 *
 * Every way a call can end without an answer writes an always-on journal line with its reason, how long it
 * took and whether the copier process was still alive ([DriveDiagnostics]).
 */
class DriveCopyClient(context: Context, private val honorStop: Boolean = true) : DriveCopier {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    override val where: String get() = "the :drive process"

    @Volatile private var service: Messenger? = null
    @Volatile private var connection: ServiceConnection? = null
    @Volatile private var connected = CompletableDeferred<Boolean>()
    @Volatile private var pending: CompletableDeferred<Bundle?>? = null
    @Volatile private var bindStartedAt = 0L

    /** How a call ended. Only [Reply] carries data. */
    sealed interface Outcome {
        class Reply(val data: Bundle) : Outcome
        /** The copier process went away mid-call (Drive's own process dying takes it down), or never came up. */
        data object Died : Outcome
        /** No answer within the limit; the copier was let go. */
        data object TimedOut : Outcome
        /** "Stop for now" was pressed; the copier was let go. */
        data object Stopped : Outcome
    }

    /** The reason the last call did not end in a [Outcome.Reply], in words; "" while none has failed. */
    @Volatile var lastFailure: String = ""
        private set

    private fun journal(line: String) = DriveCopyJournal.record(appContext, line)

    private fun fail(outcome: Outcome, why: String): Outcome {
        lastFailure = why
        journal("copier call failed: $why (copier process alive=${DriveDiagnostics.copierAlive(appContext)}, ${DriveDiagnostics.memory(appContext)})")
        return outcome
    }

    /** The sequence number of the last call sent; each call gets the next one. */
    private var lastSeq = 0

    /**
     * Receives the reply to ONE call: completes [target] only when the reply's `arg1` is [expectedSeq], and hands
     * anything else to [onStray] (journalled, then dropped).
     */
    class ReplyHandler(
        looper: Looper,
        private val expectedSeq: Int,
        private val target: CompletableDeferred<Bundle?>,
        private val onStray: (Int) -> Unit,
    ) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            if (msg.arg1 == expectedSeq) target.complete(msg.data) else onStray(msg.arg1)
        }
    }

    private fun bind(): Boolean {
        close()
        connected = CompletableDeferred()
        bindStartedAt = SystemClock.elapsedRealtime()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = Messenger(binder)
                journal("copier bind: connected after ${SystemClock.elapsedRealtime() - bindStartedAt}ms")
                connected.complete(true)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
                journal("copier bind: the :drive process went away (onServiceDisconnected) after ${SystemClock.elapsedRealtime() - bindStartedAt}ms; ${DriveDiagnostics.copierExits(appContext, 1)}")
                pending?.complete(null)
            }

            override fun onBindingDied(name: ComponentName) {
                service = null
                journal("copier bind: binding died (onBindingDied) after ${SystemClock.elapsedRealtime() - bindStartedAt}ms; ${DriveDiagnostics.copierExits(appContext, 1)}")
                pending?.complete(null)
                connected.complete(false)
            }

            override fun onNullBinding(name: ComponentName) {
                journal("copier bind: the service returned no binder (onNullBinding)")
                connected.complete(false)
            }
        }
        connection = conn
        val bound = try {
            appContext.bindService(Intent(appContext, DriveCopyService::class.java), conn, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            journal("copier bind: bindService threw ${t.javaClass.simpleName}: ${t.message}")
            false
        }
        journal("copier bind: bindService returned $bound")
        return bound
    }

    /** Sends [what] with [data] and waits at most [timeoutMs] for the answer. */
    override suspend fun call(what: Int, data: Bundle, timeoutMs: Long): Outcome {
        val label = if (what == DriveCopyProtocol.MSG_LIST) "LIST" else "COPY"
        if (service == null) {
            if (!bind()) {
                return fail(Outcome.Died, "bindService refused (the system would not start the :drive process)")
            }
            val up = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() }
            if (up != true) {
                val why = if (up == null) "the :drive process did not connect within ${CONNECT_TIMEOUT_MS}ms" else "the :drive service refused the connection"
                close()
                return fail(Outcome.Died, why)
            }
        }
        val done = CompletableDeferred<Bundle?>()
        pending = done
        val seq = ++lastSeq
        val replyTo = Messenger(ReplyHandler(Looper.getMainLooper(), seq, done) { got ->
            journal("copier reply DROPPED: $label call #$seq got a reply numbered #$got, which belongs to another call")
        })
        val sent = runCatching {
            service?.send(Message.obtain(null, what).also { it.data = data; it.arg1 = seq; it.replyTo = replyTo })
        }.onFailure { journal("copier send: $label threw ${it.javaClass.simpleName}: ${it.message}") }
            .isSuccess && service != null
        if (!sent) {
            close()
            return fail(Outcome.Died, "could not send $label to the :drive process")
        }
        val start = SystemClock.elapsedRealtime()
        while (true) {
            withTimeoutOrNull(POLL_MS) { done.await() }
            if (done.isCompleted) {
                pending = null
                val reply = done.await()
                if (reply == null) {
                    close()
                    return fail(Outcome.Died, "the :drive process dropped the connection while $label was waiting, after ${SystemClock.elapsedRealtime() - start}ms; ${DriveDiagnostics.copierExits(appContext, 1)}")
                }
                return Outcome.Reply(reply)
            }
            if (obeysStop(honorStop, what) && DriveCopyStop.isRequested()) {
                pending = null
                close()
                lastFailure = "stopped by the user"
                journal("copier call $label abandoned: Stop for now pressed after ${SystemClock.elapsedRealtime() - start}ms")
                return Outcome.Stopped
            }
            val waited = SystemClock.elapsedRealtime() - start
            if (waited > timeoutMs) {
                pending = null
                close()
                return fail(Outcome.TimedOut, "no reply to $label within ${waited}ms (limit ${timeoutMs}ms)")
            }
        }
    }

    /** Lets go of the copier, which ends its process. Safe to call twice. */
    override fun close() {
        val conn = connection ?: return
        connection = null
        service = null
        runCatching { appContext.unbindService(conn) }
            .onFailure { AppLogger.d(TAG, "unbind: ${it.message}") }
    }

    companion object {
        private const val TAG = "CV:DriveCopyClient"
        private const val POLL_MS = 500L

        /**
         * Whether a call of kind [what] is abandoned on "Stop for now". Never the LIST: it runs before the copy
         * notification (the only place the button is) is posted, so a press it could see belongs to an earlier
         * run (review C21).
         */
        fun obeysStop(honorStop: Boolean, what: Int): Boolean = honorStop && what != DriveCopyProtocol.MSG_LIST
        private const val CONNECT_TIMEOUT_MS = 15_000L
    }
}

/**
 * The same work as [DriveCopyService], done in the app's own process: the fallback when the copier process
 * cannot answer a listing. It runs each message on a worker thread so the caller keeps its time limit and
 * the Stop button; a message stuck in the provider is abandoned (its thread is left to finish on its own).
 */
class LocalDriveCopier(context: Context, private val honorStop: Boolean = true) : DriveCopier {

    private val appContext = context.applicationContext

    override val where: String get() = "the app's own process"

    override suspend fun call(what: Int, data: Bundle, timeoutMs: Long): DriveCopyClient.Outcome {
        val done = CompletableDeferred<Result<Bundle>>()
        Thread({
            done.complete(runCatching { DriveCopyHandler.handle(appContext, what, data, inCopier = false) })
        }, "cv-drive-copy-local").start()
        val start = SystemClock.elapsedRealtime()
        while (true) {
            withTimeoutOrNull(POLL_MS) { done.await() }
            if (done.isCompleted) {
                val r = done.await()
                return r.getOrNull()?.let { DriveCopyClient.Outcome.Reply(it) } ?: run {
                    DriveCopyJournal.record(appContext, "main-process copier: message $what threw ${r.exceptionOrNull()?.javaClass?.simpleName}: ${r.exceptionOrNull()?.message}")
                    DriveCopyClient.Outcome.Died
                }
            }
            if (DriveCopyClient.obeysStop(honorStop, what) && DriveCopyStop.isRequested()) {
                DriveCopyJournal.record(appContext, "main-process copier: message $what abandoned, Stop for now pressed after ${SystemClock.elapsedRealtime() - start}ms (its thread is left to finish)")
                return DriveCopyClient.Outcome.Stopped
            }
            val waited = SystemClock.elapsedRealtime() - start
            if (waited > timeoutMs) {
                DriveCopyJournal.record(appContext, "main-process copier: no result for message $what within ${waited}ms (limit ${timeoutMs}ms); its thread is left to finish, ${DriveDiagnostics.memory(appContext)}")
                return DriveCopyClient.Outcome.TimedOut
            }
        }
    }

    override fun close() = Unit

    private companion object {
        const val POLL_MS = 500L
    }
}
