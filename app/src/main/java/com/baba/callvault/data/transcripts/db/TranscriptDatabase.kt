/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Transcripts, deliberately in their **own** database rather than in
 * [com.baba.callvault.data.recordings.db.RecordingDatabase].
 *
 * That database uses `fallbackToDestructiveMigration(dropAllTables = true)`, which is right for it
 * and fatal for us. The recordings catalog is a derived cache: it can be re-seeded from the SAF
 * folders, so dropping it on a schema bump costs nothing. A transcript is the opposite — it costs
 * minutes of device CPU and cannot be regenerated from anything cheaper than the audio it came from,
 * at the same cost again. Sharing that database would mean the next unrelated schema change silently
 * destroys every transcript the user has.
 *
 * **This database must therefore NEVER be given a destructive fallback.** Schema changes here get
 * real, hand-written, tested migrations. `exportSchema = true` so those migrations have a schema
 * to diff against, which the recordings catalog deliberately does without.
 *
 * Rows are keyed by `RecordingEntry.displayName`, the catalog's natural key, so transcripts survive
 * the catalog being dropped and re-seeded.
 */
@Database(
    entities = [
        TranscriptEntry::class,
        TranscriptSegmentEntry::class,
        TranscriptSegmentFts::class,
        RecordingNoteEntry::class,
        RecordingNoteFts::class,
        RecordingWaveformEntry::class,
        CallSummaryEntry::class,
        CallSummaryFts::class,
        SpeakerTurnsEntry::class,
        RecordingTagEntry::class,
        RecordingFavouriteEntry::class,
        RecordingFlagEntry::class,
        RecordingLabelEntry::class
    ],
    version = 9,
    exportSchema = true
)
@TypeConverters(TranscriptStateConverter::class)
abstract class TranscriptDatabase : RoomDatabase() {

    abstract fun transcriptDao(): TranscriptDao

    abstract fun noteDao(): RecordingNoteDao

    abstract fun summaryDao(): CallSummaryDao

    abstract fun speakerTurnsDao(): SpeakerTurnsDao

    abstract fun tagDao(): RecordingTagDao

    abstract fun favouriteDao(): RecordingFavouriteDao

    abstract fun flagDao(): RecordingFlagDao

    abstract fun labelDao(): RecordingLabelDao

    companion object {

        private const val DB_NAME = "transcripts.db"

        /**
         * v1 → v2: notes and cached waveforms.
         *
         * Hand-written rather than destructive because of what is in here: a note is the user's own
         * words and cannot be regenerated from anything. `CREATE TABLE IF NOT EXISTS` with the exact
         * column types Room expects, so the schema it validates against on open matches.
         */
        internal val MIGRATION_1_2_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `recording_notes` (" +
                "`displayName` TEXT NOT NULL, " +
                "`text` TEXT NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`displayName`))",
            "CREATE TABLE IF NOT EXISTS `recording_waveforms` (" +
                "`displayName` TEXT NOT NULL, " +
                "`peaks` TEXT NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`displayName`))"
        )

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) = MIGRATION_1_2_SQL.forEach(db::execSQL)
        }

        /**
         * v2 → v3: summaries.
         *
         * Hand-written for the same reason as [MIGRATION_1_2], and a stronger one. A summary costs
         * roughly ninety seconds of the user's CPU on top of the transcription it was read from, so
         * dropping the table on a schema bump would quietly spend their battery twice. The column
         * types are exactly what Room expects for [CallSummaryEntry], so the schema it validates
         * against on open matches what this creates.
         */
        internal val MIGRATION_2_3_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `call_summaries` (" +
                "`displayName` TEXT NOT NULL, " +
                "`document` TEXT NOT NULL, " +
                "`model` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`displayName`))"
        )

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) = MIGRATION_2_3_SQL.forEach(db::execSQL)
        }

        /**
         * v3 → v4: who was speaking when.
         *
         * Hand-written like the rest, and this table has the strongest claim of any of them to
         * survive a schema change: speaker turns are read off the two capture channels during the
         * call and **cannot be recovered afterwards at any price**. The recording is downmixed to
         * mono before it is written, so the finished file no longer carries the directions. Lose
         * this row and the only way back is to make the call again.
         */
        internal val MIGRATION_3_4_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `speaker_turns` (" +
                "`displayName` TEXT NOT NULL, " +
                "`turns` TEXT NOT NULL, " +
                "`outgoing` INTEGER NOT NULL, " +
                "`observedMap` TEXT NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`displayName`))"
        )

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) = MIGRATION_3_4_SQL.forEach(db::execSQL)
        }

        /**
         * v4 → v5: summaries and notes join the search index.
         *
         * Until now only transcript segments were searchable, which put the two most *deliberate*
         * pieces of text in the database out of reach: the summary, where a call's outcome is
         * written in words that were often never spoken aloud, and the note, the only text here a
         * person typed themselves.
         *
         * **The summary is indexed through a new `searchText` column, never through `document`.**
         * `document` is JSON, so indexing it would match on the key names — a search for *decisions*
         * would return every call that has a summary at all. The column is added empty and
         * backfilled from the parsed document by
         * [com.baba.callvault.data.transcripts.TranscriptRepository]; existing rows therefore need no
         * FTS insert here, because writing that column fires the AFTER_UPDATE trigger below.
         *
         * Notes are backfilled directly, since a note is already plain text and is in its final form
         * the moment it is written.
         *
         * The `CREATE` statements and the four sync triggers per table are byte-for-byte what Room
         * generates for these entities — copied from the exported schema rather than composed by
         * hand, because Room validates the schema on open and a difference of one backtick is a
         * crash on every upgrading device.
         */
        internal val MIGRATION_4_5_SQL = listOf(
            "ALTER TABLE `call_summaries` ADD COLUMN `searchText` TEXT NOT NULL DEFAULT ''",

            "CREATE VIRTUAL TABLE IF NOT EXISTS `call_summaries_fts` USING FTS4(" +
                "`searchText` TEXT NOT NULL, tokenize=unicode61, content=`call_summaries`)",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_call_summaries_fts_BEFORE_UPDATE " +
                "BEFORE UPDATE ON `call_summaries` BEGIN DELETE FROM `call_summaries_fts` " +
                "WHERE `docid`=OLD.`rowid`; END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_call_summaries_fts_BEFORE_DELETE " +
                "BEFORE DELETE ON `call_summaries` BEGIN DELETE FROM `call_summaries_fts` " +
                "WHERE `docid`=OLD.`rowid`; END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_call_summaries_fts_AFTER_UPDATE " +
                "AFTER UPDATE ON `call_summaries` BEGIN INSERT INTO `call_summaries_fts`" +
                "(`docid`, `searchText`) VALUES (NEW.`rowid`, NEW.`searchText`); END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_call_summaries_fts_AFTER_INSERT " +
                "AFTER INSERT ON `call_summaries` BEGIN INSERT INTO `call_summaries_fts`" +
                "(`docid`, `searchText`) VALUES (NEW.`rowid`, NEW.`searchText`); END",

            "CREATE VIRTUAL TABLE IF NOT EXISTS `recording_notes_fts` USING FTS4(" +
                "`text` TEXT NOT NULL, tokenize=unicode61, content=`recording_notes`)",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_recording_notes_fts_BEFORE_UPDATE " +
                "BEFORE UPDATE ON `recording_notes` BEGIN DELETE FROM `recording_notes_fts` " +
                "WHERE `docid`=OLD.`rowid`; END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_recording_notes_fts_BEFORE_DELETE " +
                "BEFORE DELETE ON `recording_notes` BEGIN DELETE FROM `recording_notes_fts` " +
                "WHERE `docid`=OLD.`rowid`; END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_recording_notes_fts_AFTER_UPDATE " +
                "AFTER UPDATE ON `recording_notes` BEGIN INSERT INTO `recording_notes_fts`" +
                "(`docid`, `text`) VALUES (NEW.`rowid`, NEW.`text`); END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_recording_notes_fts_AFTER_INSERT " +
                "AFTER INSERT ON `recording_notes` BEGIN INSERT INTO `recording_notes_fts`" +
                "(`docid`, `text`) VALUES (NEW.`rowid`, NEW.`text`); END",

            "INSERT INTO `recording_notes_fts`(`docid`, `text`) " +
                "SELECT `rowid`, `text` FROM `recording_notes`"
        )

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) = MIGRATION_4_5_SQL.forEach(db::execSQL)
        }

        /**
         * v5 → v6: tags.
         *
         * Hand-written like the rest, and a tag has the same claim on surviving as a note does: it is
         * a judgement the user made about a call — *this one was about the flat* — and nothing on
         * disk records it, so a destructive bump would simply lose it.
         *
         * The composite primary key is the deduplication: applying a tag twice writes the same row
         * rather than a second one. The index on `tag` is what keeps filtering cheap once somebody
         * has years of calls.
         */
        internal val MIGRATION_5_6_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `recording_tags` (" +
                "`displayName` TEXT NOT NULL, " +
                "`tag` TEXT NOT NULL, " +
                "PRIMARY KEY(`displayName`, `tag`))",
            "CREATE INDEX IF NOT EXISTS `index_recording_tags_tag` ON `recording_tags` (`tag`)"
        )

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) = MIGRATION_5_6_SQL.forEach(db::execSQL)
        }

        /**
         * v6 → v7: starred recordings.
         *
         * Presence in the table is the state, so there is nothing to backfill — every existing
         * recording is correctly unstarred by having no row. Hand-written like the rest, because this
         * database has no destructive fallback and a star now carries a promise: a starred recording
         * is exempt from the retention sweep and the storage cap, so losing this table would silently
         * hand the sweeps recordings the user had protected.
         */
        internal val MIGRATION_6_7_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `recording_favourites` (" +
                "`displayName` TEXT NOT NULL, " +
                "PRIMARY KEY(`displayName`))"
        )

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) = MIGRATION_6_7_SQL.forEach(db::execSQL)
        }

        /**
         * v7 → v8: marked moments.
         *
         * Nothing to backfill: a recording made before this existed has no marks, which is exactly
         * what having no rows means. Hand-written like the rest — this database has no destructive
         * fallback, and a mark is the user's own note about where something was said, which cannot
         * be regenerated from the audio.
         */
        internal val MIGRATION_7_8_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `recording_flags` (" +
                "`displayName` TEXT NOT NULL, " +
                "`atMs` INTEGER NOT NULL, " +
                "PRIMARY KEY(`displayName`, `atMs`))",
            "CREATE INDEX IF NOT EXISTS `index_recording_flags_displayName` ON `recording_flags` (`displayName`)"
        )

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) = MIGRATION_7_8_SQL.forEach(db::execSQL)
        }

        /**
         * v8 → v9: the last-known name and date of each recording, for the Transcripts and Summaries
         * pages to title a row before the recordings list has loaded.
         *
         * Nothing to backfill: the table is filled the first time the list loads after the upgrade,
         * which is the first thing the app does. Hand-written like the rest because this database
         * has no destructive fallback, and a destructive bump here would take the notes, tags and
         * stars with it for the sake of a cache.
         */
        internal val MIGRATION_8_9_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `recording_labels` (" +
                "`displayName` TEXT NOT NULL, " +
                "`label` TEXT NOT NULL, " +
                "`subtitle` TEXT, " +
                "PRIMARY KEY(`displayName`))"
        )

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) = MIGRATION_8_9_SQL.forEach(db::execSQL)
        }

        /**
         * Every migration, in one place, used by both [get] and the migration test.
         *
         * One list rather than two so a migration that is written but never registered cannot
         * happen — that mistake would look exactly like a correct build until an upgrading user
         * opened the app, and this database has no destructive fallback to catch them.
         */
        internal val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)

        @Volatile
        private var INSTANCE: TranscriptDatabase? = null

        fun get(context: Context): TranscriptDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TranscriptDatabase::class.java,
                    DB_NAME
                ).addMigrations(*MIGRATIONS)
                    .build().also { INSTANCE = it } // no destructive fallback — see the class KDoc
            }

        /**
         * Whether a transcripts database has ever been created.
         *
         * Lets the delete cascade skip its work entirely on a device that has never transcribed
         * anything, instead of calling [get] and materialising an empty database as a side effect of
         * deleting a recording.
         *
         * An already-open [INSTANCE] counts on its own: the database plainly exists once this process
         * has opened it, and Room creates the file lazily on first write, so the file check alone can
         * report "no database" while one is open and holding rows.
         */
        fun exists(context: Context): Boolean =
            INSTANCE != null || context.applicationContext.getDatabasePath(DB_NAME).exists()
    }
}
