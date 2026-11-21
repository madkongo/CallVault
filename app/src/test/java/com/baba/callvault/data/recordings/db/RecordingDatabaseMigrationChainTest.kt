/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the one place the destructive fallback would be genuinely destructive (C136): a schema bump
 * shipped without a migration silently drops every merge manifest, and merged recordings become
 * permanently un-splittable. [RecordingDatabase] keeps its destructive fallback on purpose, so this
 * test is what turns "forgot the migration" into a red build instead of lost data.
 */
class RecordingDatabaseMigrationChainTest {

    @Test
    fun everySchemaVersionHasAMigrationToTheNext() {
        val migrations = RecordingDatabase.ALL_MIGRATIONS

        for (from in 1 until RecordingDatabase.SCHEMA_VERSION) {
            val step = migrations.filter { it.startVersion == from && it.endVersion == from + 1 }
            assertEquals(
                "No single-step migration $from -> ${from + 1}. Bumping RecordingDatabase.SCHEMA_VERSION " +
                    "without one makes the destructive fallback drop merge_parts for every user.",
                1,
                step.size,
            )
        }
    }

    @Test
    fun noMigrationGoesBackwardsOrPastTheCurrentVersion() {
        for (m in RecordingDatabase.ALL_MIGRATIONS) {
            assertTrue("Migration ${m.startVersion} -> ${m.endVersion} does not move forward", m.endVersion > m.startVersion)
            assertTrue(
                "Migration ${m.startVersion} -> ${m.endVersion} ends past SCHEMA_VERSION ${RecordingDatabase.SCHEMA_VERSION}",
                m.endVersion <= RecordingDatabase.SCHEMA_VERSION,
            )
        }
    }
}
