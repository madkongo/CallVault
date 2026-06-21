/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database hosting CallVault's recordings catalog. Single table, single process. A plain
 * process-wide singleton ([get]) since there is no DI container in the app.
 *
 * The catalog is a derived, rebuildable cache (it can be re-seeded from the SAF folders), so schema
 * changes use [RoomDatabase.Builder.fallbackToDestructiveMigration]: on a version bump the table is
 * recreated and the one-time import re-seeds it — no fragile hand-written migrations to maintain.
 */
@Database(entities = [RecordingEntry::class], version = 1, exportSchema = false)
abstract class RecordingDatabase : RoomDatabase() {

    abstract fun recordingDao(): RecordingDao

    companion object {
        @Volatile
        private var INSTANCE: RecordingDatabase? = null

        fun get(context: Context): RecordingDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RecordingDatabase::class.java,
                    "recordings.db"
                ).fallbackToDestructiveMigration(dropAllTables = true).build().also { INSTANCE = it }
            }
    }
}
