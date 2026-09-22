/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts.db

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Guards the hand-written migrations against the schema drifting out from under them.
 *
 * [TranscriptDatabase] has no destructive fallback, deliberately: a transcript costs minutes of the
 * user's CPU and a summary costs ninety seconds more, so dropping the tables on a schema bump would
 * spend their battery twice over. The price of that decision is that a migration which does not
 * produce exactly what Room expects is not a lost cache — it is a crash on open, for every user who
 * has ever transcribed a call, with no way back.
 *
 * **What this proves.** Room exports the schema it will validate against into `app/schemas/`. This
 * compares each migration's `CREATE TABLE` against that exported truth, character for character.
 * The realistic failure it catches is drift rather than a typo today: someone adds a column to
 * [CallSummaryEntry] months from now, Room's expectation moves, and the migration silently does not.
 *
 * **What it does not prove.** It does not open a v2 database and upgrade it. Doing that faithfully
 * needs Room's own `MigrationTestHelper` reading these same files, which under Robolectric means
 * merging the schema directory into test assets. That is worth adding the day a migration does more
 * than add a table — one that transforms existing rows can be perfectly well-formed and still lose
 * data, and nothing here would notice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptSchemaMigrationTest {

    @Test
    fun `the summaries migration creates exactly the table Room expects at v3`() {
        val expected = createSqlFor(version = 3, table = "call_summaries")

        assertEquals(expected, sqlIn(TranscriptDatabase.MIGRATION_2_3_SQL.single()))
    }

    @Test
    fun `the notes migration still creates exactly the tables Room expects`() {
        // MIGRATION_1_2 shipped untested. It is right — this is what says so, and keeps saying so.
        val statements = TranscriptDatabase.MIGRATION_1_2_SQL.map(::sqlIn)

        assertTrue(createSqlFor(version = 2, table = "recording_notes") in statements)
        assertTrue(createSqlFor(version = 2, table = "recording_waveforms") in statements)
    }

    @Test
    fun `the speaker turns migration creates exactly the table Room expects at v4`() {
        val expected = createSqlFor(version = 4, table = "speaker_turns")

        assertEquals(expected, sqlIn(TranscriptDatabase.MIGRATION_3_4_SQL.single()))
    }

    @Test
    fun `the favourites migration creates exactly the table Room expects at v7`() {
        val expected = createSqlFor(version = 7, table = "recording_favourites")

        assertEquals(expected, sqlIn(TranscriptDatabase.MIGRATION_6_7_SQL.single()))
    }

    @Test
    fun `the marks migration creates exactly the table Room expects at v8`() {
        val expected = createSqlFor(version = 8, table = "recording_flags")

        assertEquals(expected, sqlIn(TranscriptDatabase.MIGRATION_7_8_SQL.first()))
    }

    @Test
    fun `the labels migration creates exactly the table Room expects at v9`() {
        val expected = createSqlFor(version = 9, table = "recording_labels")

        assertEquals(expected, sqlIn(TranscriptDatabase.MIGRATION_8_9_SQL.single()))
    }

    @Test
    fun `every table added since v1 is created by a migration`() {
        // The check that survives someone adding an entity: if a table exists at the current version
        // but was not in v1 and no migration creates it, upgrading users never get it.
        val migrated = (
            TranscriptDatabase.MIGRATION_1_2_SQL +
                TranscriptDatabase.MIGRATION_2_3_SQL +
                TranscriptDatabase.MIGRATION_3_4_SQL +
                TranscriptDatabase.MIGRATION_4_5_SQL +
                TranscriptDatabase.MIGRATION_5_6_SQL +
                TranscriptDatabase.MIGRATION_6_7_SQL +
                TranscriptDatabase.MIGRATION_7_8_SQL +
                TranscriptDatabase.MIGRATION_8_9_SQL
            ).map(::sqlIn)
        val original = tablesAt(version = 1)

        tablesAt(version = LATEST_VERSION).forEach { table ->
            if (table in original) return@forEach
            // Compared at the version the table was *introduced*, not at the latest one. A table can
            // legitimately differ from its original `CREATE` later on, because a subsequent migration
            // altered it — `call_summaries` gained `searchText` at v5 — and asserting against the
            // newest shape would report that as a missing migration. What actually matters is that
            // every table is created by some migration at the point it first exists; the ALTERs that
            // follow are covered by the tests for the migrations that make them.
            val introduced = firstVersionWith(table)
            assertTrue(
                "No migration creates `$table`, so anyone upgrading never gets it",
                migrated.any { createSqlFor(introduced, table) == it }
            )
        }
    }

    /** The earliest exported version whose schema contains [table]. */
    private fun firstVersionWith(table: String): Int =
        (1..LATEST_VERSION).firstOrNull { table in tablesAt(it) }
            ?: error("`$table` is in no exported schema at all")

    @Test
    fun `the search migration creates exactly the FTS tables Room expects at v5`() {
        val statements = TranscriptDatabase.MIGRATION_4_5_SQL.map(::sqlIn)

        assertTrue(createSqlFor(version = 5, table = "call_summaries_fts") in statements)
        assertTrue(createSqlFor(version = 5, table = "recording_notes_fts") in statements)
    }

    @Test
    fun `the search migration creates every FTS sync trigger Room expects`() {
        // The triggers are the half of an external-content FTS table that `createSql` does not cover,
        // and the half that fails quietly: without them the index is created, the schema validates,
        // and the table simply never learns about anything written afterwards. Search would keep
        // working for whatever was backfilled and silently miss every summary written from then on.
        val statements = TranscriptDatabase.MIGRATION_4_5_SQL.map(::sqlIn)

        listOf("call_summaries_fts", "recording_notes_fts").forEach { table ->
            triggersFor(version = 5, table = table).forEach { trigger ->
                assertTrue("Migration is missing: $trigger", trigger in statements)
            }
        }
    }

    @Test
    fun `the search migration adds the summary column the FTS index reads`() {
        // The FTS table is declared over `searchText`, so creating the index without the column is a
        // crash on open rather than a missing feature.
        assertTrue(
            TranscriptDatabase.MIGRATION_4_5_SQL.any {
                "ADD COLUMN `searchText`" in it && "call_summaries" in it
            }
        )
    }

    @Test
    fun `existing notes are backfilled into the search index`() {
        // A note written before v5 is a note the user typed themselves. The FTS triggers only fire on
        // writes *after* they exist, so without an explicit backfill every note already on the phone
        // would stay unsearchable for ever — the exact failure this feature exists to remove.
        assertTrue(
            TranscriptDatabase.MIGRATION_4_5_SQL.any {
                "INSERT INTO `recording_notes_fts`" in it && "SELECT" in it
            }
        )
    }

    @Test
    fun `the tags migration creates exactly the table Room expects at v6`() {
        val statements = TranscriptDatabase.MIGRATION_5_6_SQL.map(::sqlIn)

        assertTrue(createSqlFor(version = 6, table = "recording_tags") in statements)
    }

    @Test
    fun `the tags migration creates the index the filter depends on`() {
        // Without it, filtering by a tag is a full scan of every assignment on every keystroke of the
        // list. It would be correct and it would get slower every year.
        assertTrue(
            TranscriptDatabase.MIGRATION_5_6_SQL.any {
                "index_recording_tags_tag" in it && "CREATE INDEX" in it
            }
        )
    }

    /** Room's own content-sync triggers for an external-content FTS [table] at [version]. */
    private fun triggersFor(version: Int, table: String): List<String> {
        val entities = schema(version).getJSONObject("database").getJSONArray("entities")
        val entity = (0 until entities.length())
            .map { entities.getJSONObject(it) }
            .firstOrNull { it.getString("tableName") == table }
            ?: error("v$version has no table `$table`")
        val triggers = entity.getJSONArray("contentSyncTriggers")
        return (0 until triggers.length()).map { sqlIn(triggers.getString(it)) }
    }

    /** Room's own `CREATE TABLE` for [table] at [version], with its table-name placeholder resolved. */
    private fun createSqlFor(version: Int, table: String): String {
        val entities = schema(version).getJSONObject("database").getJSONArray("entities")
        val entity = (0 until entities.length())
            .map { entities.getJSONObject(it) }
            .firstOrNull { it.getString("tableName") == table }
            ?: error("v$version has no table `$table`; the exported schema and this test disagree")
        return sqlIn(entity.getString("createSql").replace("\${TABLE_NAME}", table))
    }

    private fun tablesAt(version: Int): Set<String> {
        val entities = schema(version).getJSONObject("database").getJSONArray("entities")
        return (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }.toSet()
    }

    private fun schema(version: Int): JSONObject {
        val file = File(SCHEMA_DIR, "$version.json")
        check(file.exists()) {
            "Missing ${file.path}. Room exports schemas on build; run the tests from the app module."
        }
        return JSONObject(file.readText())
    }

    /** Collapses whitespace so a reformatted string concatenation is not a failing test. */
    private fun sqlIn(sql: String): String = sql.replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val LATEST_VERSION = 9
        const val SCHEMA_DIR = "schemas/com.baba.callvault.data.transcripts.db.TranscriptDatabase"
    }
}
