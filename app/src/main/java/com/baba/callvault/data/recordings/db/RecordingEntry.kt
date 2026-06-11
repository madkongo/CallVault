/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row of CallVault's own recordings catalog — the app's source of truth for the Home list.
 *
 * The catalog exists so CallVault never has to ask a cloud provider (Google Drive) "what files do you
 * have?", whose folder listing is cached and eventually-consistent (the cause of "phantom" recordings
 * and the Drive sync-error toast). Instead, every time CallVault writes or moves a recording it records
 * the resulting SAF document URIs here, and Home reads from this table — instant, offline, and immune
 * to a provider's stale listing.
 *
 * [displayName] is the natural key: CallVault's filename template embeds a millisecond timestamp, so a
 * name uniquely identifies a recording, and the device + Drive copies of the same recording share it.
 * That lets the copy/sweep workers upsert the Drive copy onto the same row by name.
 *
 * Call direction / date / number are intentionally NOT stored: they are derived from [displayName]
 * (the template encodes them), which keeps the row lean and makes importing pre-existing files trivial.
 *
 * @param displayName     The recording's file name including extension (primary key).
 * @param localUri        The device-folder copy's content URI string, or null if there is no local copy
 *                        (DRIVE-only mode after sync, or the local copy was deleted).
 * @param driveUri        The Drive-folder copy's content URI string, or null if not (yet) synced.
 * @param localSizeBytes  Size of the device copy in bytes, or null when there is no device copy.
 * @param driveSizeBytes  Size of the Drive copy in bytes, or null when there is no Drive copy.
 * @param lastModified    Best-known last-modified / creation epoch millis, used for newest-first sort.
 */
@Entity(tableName = "recordings")
data class RecordingEntry(
    @PrimaryKey val displayName: String,
    val localUri: String? = null,
    val driveUri: String? = null,
    val localSizeBytes: Long? = null,
    val driveSizeBytes: Long? = null,
    val lastModified: Long = 0L
)
