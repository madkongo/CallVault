/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import java.io.File

/**
 * Removes the temp files a killed merge leaves behind (review R34). Each can be a whole call's size and nothing
 * ever shows them: the cut-off rescue only looks at `rec_stage_*`, and the merge's Drive downloads live in cache.
 *
 * What it deletes, and why that cannot lose a call:
 *  - `merge_stage_*.tmp` in the staging folder: a merge joins into it and publishes a COPY, and removes the parts
 *    only after the merged file is saved (C4), so a leftover is never the only copy of anything.
 *  - Files in `cacheDir/merge-temp`: copies of Drive recordings downloaded for a merge; the Drive original stays.
 *
 * What it NEVER touches: any `rec_stage_*` file or its note. A staged call recording is what the cut-off rescue
 * saves; whether one without a note is a lost call or a stale temp cannot be told (see [CutOffRescuePolicy]), so
 * those are only counted, for the log. Files younger than [MAX_AGE_MS] and files a live recording owns are kept.
 */
object StaleTempSweep {

    /** A merge runs for minutes; a day-old temp is abandoned. */
    const val MAX_AGE_MS = 24L * 60 * 60_000L

    data class Result(
        val mergeStageDeleted: Int = 0,
        val mergeTempDeleted: Int = 0,
        val bytesFreed: Long = 0L,
        /** `rec_stage_*` files with no note beside them: kept, reported so a report can say how much sits there. */
        val noteLessStagedCount: Int = 0,
        val noteLessStagedBytes: Long = 0L,
    ) {
        val anythingToSay: Boolean get() = mergeStageDeleted > 0 || mergeTempDeleted > 0 || noteLessStagedCount > 0
    }

    fun run(
        stagingDir: File?,
        mergeTempDir: File?,
        nowMs: Long,
        isLive: (File) -> Boolean = LiveStagingFiles::isLive,
    ): Result {
        var mergeStage = 0
        var mergeTemp = 0
        var freed = 0L
        var noteLess = 0
        var noteLessBytes = 0L
        stagingDir?.listFiles().orEmpty().forEach { file ->
            when {
                SafHelper.isMergeStaging(file) && isStale(file, nowMs) && !isLive(file) -> {
                    val size = file.length()
                    if (runCatching { file.delete() }.getOrDefault(false)) { mergeStage++; freed += size }
                }
                SafHelper.isStagedRecording(file) && !StagingNote.fileFor(file).exists() && file.length() > 0L -> {
                    noteLess++; noteLessBytes += file.length()
                }
            }
        }
        mergeTempDir?.listFiles().orEmpty().forEach { file ->
            if (file.isFile && isStale(file, nowMs)) {
                val size = file.length()
                if (runCatching { file.delete() }.getOrDefault(false)) { mergeTemp++; freed += size }
            }
        }
        return Result(mergeStage, mergeTemp, freed, noteLess, noteLessBytes)
    }

    /** Whether [stagingDir] or [mergeTempDir] holds a stale temp, so the start-up check knows to queue the sweep. */
    fun hasStale(stagingDir: File?, mergeTempDir: File?, nowMs: Long): Boolean =
        stagingDir?.listFiles().orEmpty().any { SafHelper.isMergeStaging(it) && isStale(it, nowMs) } ||
            mergeTempDir?.listFiles().orEmpty().any { it.isFile && isStale(it, nowMs) }

    private fun isStale(file: File, nowMs: Long): Boolean = nowMs - file.lastModified() >= MAX_AGE_MS
}
