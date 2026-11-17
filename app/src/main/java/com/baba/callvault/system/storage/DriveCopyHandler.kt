/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import android.os.Bundle
import android.os.SystemClock

/**
 * What the Drive copier does with one message. Shared by [DriveCopyService] (the `:drive` process) and
 * [LocalDriveCopier] (the same work in the app's own process, used only when the copier process cannot list
 * the folder), so both answer the same way and both write the same journal lines.
 *
 * Every failure here ends in a reply that carries a reason, and in a journal line: there is no path that
 * returns "failed" without saying why.
 */
object DriveCopyHandler {

    /**
     * Test hook for the drive2 build: when true, the `:drive` process refuses to list (the main-process
     * fallback never does), to show the fallback and its log lines on a phone. False in every shipped
     * build; a unit test fails if it is ever committed as true.
     */
    const val TEST_FORCE_LIST_FAILURE = false

    fun handle(context: Context, what: Int, data: Bundle, inCopier: Boolean): Bundle {
        val where = if (inCopier) "copier" else "main-process copier"
        fun log(line: String) = DriveCopyJournal.record(context, line, fromCopier = inCopier)
        val start = SystemClock.elapsedRealtime()
        return try {
            when (what) {
                DriveCopyProtocol.MSG_LIST -> list(context, data, inCopier, where, start, ::log)
                DriveCopyProtocol.MSG_COPY -> copy(context, data, where, start, ::log)
                else -> {
                    log("$where: unknown message $what -> refused")
                    DriveCopyProtocol.failureReply("unknown message $what")
                }
            }
        } catch (t: Throwable) {
            log("$where: message $what threw ${t.javaClass.simpleName}: ${t.message} after ${SystemClock.elapsedRealtime() - start}ms")
            DriveCopyProtocol.failureReply("${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun list(
        context: Context, data: Bundle, inCopier: Boolean, where: String, start: Long, log: (String) -> Unit,
    ): Bundle {
        @Suppress("DEPRECATION")
        val folder = data.getParcelable<android.net.Uri>(DriveCopyProtocol.K_FOLDER)
        log("$where: LIST received, folder=${DriveDiagnostics.uriTag(folder)}, ${DriveDiagnostics.permission(context, folder)}, ${DriveDiagnostics.memory(context)}")
        fun fail(reason: String): Bundle {
            log("$where: LIST failed after ${SystemClock.elapsedRealtime() - start}ms: $reason")
            return DriveCopyProtocol.failureReply(reason)
        }
        if (folder == null) return fail("the request carried no folder")
        if (TEST_FORCE_LIST_FAILURE && inCopier) return fail("TEST HOOK: forced list failure in the copier process")
        SafHelper.folderProblem(context, folder)?.let { return fail("folder not usable: $it") }
        val (listing, why) = SafHelper.listChildrenWithReason(context, folder)
        if (listing == null) return fail("listing failed: ${why ?: "no reason given"}")
        log("$where: LIST ok, ${listing.names.size} entries in ${SystemClock.elapsedRealtime() - start}ms")
        return DriveCopyProtocol.listReply(listing)
    }

    private fun copy(context: Context, data: Bundle, where: String, start: Long, log: (String) -> Unit): Bundle {
        val r = DriveCopyProtocol.readCopyRequest(data)
        if (r == null) {
            log("$where: COPY refused: the request was incomplete")
            return DriveCopyProtocol.failureReply("bad copy request")
        }
        log("$where: COPY received '${r.name}' ${r.sourceSize}B lookup=${r.lookup}")
        val timings = CopyTimings()
        val result = SafHelper.copyFileToFolder(
            context, r.src, r.folder, r.name, r.mime, r.sourceSize, timings,
            if (r.lookup) null else SafHelper.KnownDest(r.existing, r.existingSize, r.staging),
        )
        // The length the provider reports for the document the copy ended in: what a delete of the device
        // copy is later checked against. One query by URI, timed with the rest.
        val driveSize = when (result) {
            is SafHelper.CopyResult.Copied -> timings.time("verify") { SafHelper.documentSize(context, result.uri) }
            is SafHelper.CopyResult.AlreadyPresent -> timings.time("verify") { SafHelper.documentSize(context, result.uri) }
            is SafHelper.CopyResult.Failed -> -1L
        }
        val kind = when (result) {
            is SafHelper.CopyResult.Copied -> "copied"
            is SafHelper.CopyResult.AlreadyPresent -> "already there"
            is SafHelper.CopyResult.Failed -> "FAILED (${result.reason})"
        }
        log("$where: COPY done '${r.name}' $kind in ${SystemClock.elapsedRealtime() - start}ms, Drive reports $driveSize bytes")
        return DriveCopyProtocol.copyReply(result, timings, driveSize)
    }
}
