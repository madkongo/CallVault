/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Parcel
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process

/**
 * Does the Drive copy in a process of its own (`:drive`).
 *
 * Why a separate process: copying goes through Google Drive's document provider, and a process that holds
 * a connection to a provider is killed by Android the moment that provider's process dies
 * (`DEPENDENCY_DIED: depends on provider com.google.android.apps.docs/.StorageBackendContentProvider`).
 * A Honor user's phone did that 15 times in six days — each time taking down the whole app, recorder link
 * included. With every Drive query and write made here, Android kills only this process, and the app
 * notices the dropped connection and carries on.
 *
 * It never touches the app's state: the app tells it what to copy and where, and it answers with what
 * happened and how long each step took. It reads nothing from preferences or the catalog, and
 * [com.baba.callvault.CallVaultApplication] skips the app's own start-up here.
 *
 * Unbinding stops the process on purpose ([onDestroy]): that is how the app abandons a copy stuck in the
 * provider, or one the user stopped, within seconds instead of waiting for it to return.
 */
class DriveCopyService : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        journal("copier service created (pid ${Process.myPid()}), ${DriveDiagnostics.memory(applicationContext)}")
        thread = HandlerThread("cv-drive-copy").also { it.start() }
        messenger = Messenger(object : Handler(thread.looper) {
            override fun handleMessage(msg: Message) {
                val reply = DriveCopyProtocol.replyTo(msg)
                // [DriveCopyHandler.handle] never throws and journals every failure with its reason; this
                // catch is only for something outside it, and says so.
                reply.data = runCatching {
                    // Inside the copier process, where the persisted grant for the folder is read from.
                    DriveCopyHandler.handle(applicationContext, msg.what, msg.data)
                }.getOrElse {
                    journal("message ${msg.what} failed outside the handler: ${it.javaClass.simpleName}: ${it.message}")
                    DriveCopyProtocol.failureReply("${it.javaClass.simpleName}: ${it.message}")
                }
                send(msg, reply)
            }
        })
    }

    /**
     * Sends [reply] to the app. The serialised size of every list reply is journalled (always on). A reply the
     * binder refuses (too large) used to vanish silently and leave the app waiting out its time limit; now a
     * small failure reply, naming the size, goes instead so the app can fall back within seconds.
     */
    private fun send(msg: Message, reply: Message) {
        val isList = msg.what == DriveCopyProtocol.MSG_LIST
        val bytes = parcelSize(reply.data)
        val entries = reply.data.getInt(DriveCopyProtocol.K_COUNT, reply.data.getStringArray(DriveCopyProtocol.K_NAMES)?.size ?: -1)
        if (isList) {
            val file = reply.data.getString(DriveCopyProtocol.K_LIST_FILE)
            journal(
                "list reply serialised: $bytes bytes as a message, $entries entries" +
                    if (file != null) ", listing carried in a file of ${reply.data.getLong(DriveCopyProtocol.K_LIST_BYTES)} bytes" else ", listing carried inline",
            )
        }
        val sent = runCatching { msg.replyTo?.send(reply) }
        val failure = sent.exceptionOrNull() ?: return
        val cause = "${failure.javaClass.simpleName}: ${failure.message}"
        journal("reply to message ${msg.what} could not be sent ($bytes bytes, $entries entries): $cause; sending a failure reply instead")
        reply.data.getString(DriveCopyProtocol.K_LIST_FILE)?.let { runCatching { java.io.File(it).delete() } }
        val small = DriveCopyProtocol.replyTo(msg)
        small.data = DriveCopyProtocol.failureReply(DriveCopyProtocol.tooLargeReason(entries, bytes, cause))
        runCatching { msg.replyTo?.send(small) }
            .onFailure { journal("the failure reply for message ${msg.what} could not be sent either: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun parcelSize(b: Bundle): Int {
        val p = Parcel.obtain()
        return try { p.writeBundle(b); p.dataSize() } catch (t: Throwable) { -1 } finally { p.recycle() }
    }

    override fun onBind(intent: Intent?): IBinder {
        journal("copier bound")
        return messenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        journal("copier unbound by the app")
        return super.onUnbind(intent)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        journal("onTrimMemory level=$level, ${DriveDiagnostics.memory(applicationContext)}")
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        super.onLowMemory()
        journal("onLowMemory, ${DriveDiagnostics.memory(applicationContext)}")
    }

    private fun journal(line: String) = DriveCopyJournal.record(applicationContext, line, fromCopier = true)

    override fun onDestroy() {
        // The only exit this process chooses for itself; a kill by the system leaves no line here and shows in
        // the app's "copier exit records" line instead (see [DriveDiagnostics.copierExits]).
        journal("copier service destroyed; ending the process (pid ${Process.myPid()})")
        // The app let go of us. A copy still blocked inside the provider would keep this process (and its
        // connection to Drive) alive for as long as the provider takes; ending the process is the only way
        // to stop it now.
        Process.killProcess(Process.myPid())
        super.onDestroy()
    }
}
