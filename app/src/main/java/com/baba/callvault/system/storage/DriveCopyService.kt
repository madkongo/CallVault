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
import android.os.Handler
import android.os.HandlerThread
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
        thread = HandlerThread("cv-drive-copy").also { it.start() }
        messenger = Messenger(object : Handler(thread.looper) {
            override fun handleMessage(msg: Message) {
                val reply = Message.obtain(null, msg.what)
                reply.data = runCatching { handle(msg) }.getOrElse {
                    DriveCopyProtocol.failureReply("${it.javaClass.simpleName}: ${it.message}")
                }
                runCatching { msg.replyTo?.send(reply) }
            }
        })
    }

    private fun handle(msg: Message) = when (msg.what) {
        DriveCopyProtocol.MSG_LIST -> {
            val folder = msg.data.getParcelable<android.net.Uri>(DriveCopyProtocol.K_FOLDER)
            // The same check the sweep used to make itself: a folder that is gone or read-only is a retry,
            // not a sweep of files that can never land.
            val usable = folder != null && SafHelper.isFolderValid(applicationContext, folder)
            val listing = if (usable) SafHelper.listChildren(applicationContext, folder!!) else null
            if (listing == null) DriveCopyProtocol.failureReply("the Drive folder could not be listed")
            else DriveCopyProtocol.listReply(listing)
        }
        DriveCopyProtocol.MSG_COPY -> {
            val r = DriveCopyProtocol.readCopyRequest(msg.data)
                ?: return@handle DriveCopyProtocol.failureReply("bad copy request")
            val timings = CopyTimings()
            val result = SafHelper.copyFileToFolder(
                applicationContext, r.src, r.folder, r.name, r.mime, r.sourceSize, timings,
                if (r.lookup) null else SafHelper.KnownDest(r.existing, r.existingSize, r.staging),
            )
            DriveCopyProtocol.copyReply(result, timings)
        }
        else -> DriveCopyProtocol.failureReply("unknown message ${msg.what}")
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        // The app let go of us. A copy still blocked inside the provider would keep this process (and its
        // connection to Drive) alive for as long as the provider takes; ending the process is the only way
        // to stop it now.
        Process.killProcess(Process.myPid())
        super.onDestroy()
    }
}
