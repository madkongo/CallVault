/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

import android.content.Context
import kotlinx.coroutines.flow.combine
import com.baba.callvault.data.merge.HiddenMergeParts
import com.baba.callvault.data.transcripts.db.RecordingTagEntry
import com.baba.callvault.data.transcripts.db.TagCount
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.summary.CallSummary
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The user's own labels on their recordings.
 *
 * Tags answer the case contacts cannot: a great many calls are with numbers nobody has in an address
 * book, and *the flat*, *the insurance claim* or *dad's doctor* is how a person actually finds those
 * again. It is the best-evidenced gap in the whole product survey.
 *
 * Every read is guarded on the database existing, like the rest of this package: an install that has
 * never transcribed anything must not have a database created just to draw an empty chip row.
 */
object TagRepository {

    private const val TAG = "CV:TagRepository"

    private fun dao(context: Context) = TranscriptDatabase.get(context).tagDao()

    /** The tags on one recording. */
    fun tagsFor(context: Context, displayName: String): Flow<List<String>> {
        if (!TranscriptDatabase.exists(context)) return flowOf(emptyList())
        return dao(context).observeFor(displayName)
    }

    /** Every tag in use, most-used first — the filter row. */
    fun allTags(context: Context): Flow<List<TagCount>> {
        if (!TranscriptDatabase.exists(context)) return flowOf(emptyList())
        // Counted here rather than in SQL so a merged-away part's kept tags (hidden for un-merge, N2) do not count.
        return dao(context).observeAllAssignments().combine(HiddenMergeParts.observe(context)) { rows, hidden ->
            rows.filterNot { it.displayName in hidden }
                .groupingBy { it.tag }.eachCount()
                .map { (tag, count) -> TagCount(tag, count) }
                .sortedWith(compareByDescending<TagCount> { it.count }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.tag })
        }
    }

    /**
     * Which tags each recording carries, for Home's filter facet.
     *
     * A map rather than a query per tag so the list filtering stays a pure function of state, like
     * the contact and date facets it sits beside.
     */
    fun assignments(context: Context): Flow<Map<String, Set<String>>> {
        if (!TranscriptDatabase.exists(context)) return flowOf(emptyMap())
        return dao(context).observeAllAssignments().combine(HiddenMergeParts.observe(context)) { rows, hidden ->
            rows.filterNot { it.displayName in hidden }.groupBy { it.displayName }
                .mapValues { (_, entries) -> entries.map { it.tag }.toSet() }
        }
    }

    /** The recordings carrying [tag], for filtering the list. */
    fun taggedWith(context: Context, tag: String): Flow<List<String>> {
        if (!TranscriptDatabase.exists(context)) return flowOf(emptyList())
        return dao(context).observeTagged(tag)
            .combine(HiddenMergeParts.observe(context)) { names, hidden -> names.filterNot { it in hidden } }
    }

    /**
     * Applies [input] to [displayName], and returns the tag as it was actually stored.
     *
     * The return value is not decoration: [TagName.canonical] may hand back a different spelling from
     * the one typed, because an existing tag differing only in case wins. The caller needs to know
     * what was really added so it can show that, rather than the text still sitting in the field.
     *
     * Null means the input was not a tag at all — empty, or only whitespace.
     */
    suspend fun add(context: Context, displayName: String, input: String): String? {
        val existing = runCatching { dao(context).allTags() }.getOrDefault(emptyList())
        val tag = TagName.canonical(input, existing) ?: return null

        return runCatching {
            dao(context).add(RecordingTagEntry(displayName = displayName, tag = tag))
            tag
        }.getOrElse {
            // The tag itself is never logged: it is the user's word for what a private call was about.
            AppLogger.w(TAG, "Could not add a tag: ${it.message}")
            null
        }
    }

    /**
     * Renames [tag] to [input] everywhere it appears, and returns the new name.
     *
     * **The one operation that makes tagging survivable.** Without it a typo applied across twenty
     * calls can only be fixed by opening all twenty, which in practice means living with it — and one
     * misspelled tag splits a filter for good.
     *
     * Renaming onto a tag that already exists is a **merge**, and deliberately so: it is the only way
     * to repair *work* and *Work Stuff* having been coined separately. The canonicalisation excludes
     * the tag being renamed, so correcting only its capitalisation is not mistaken for a no-op.
     *
     * Null means the new name was not a tag at all, or was the same one back again.
     */
    suspend fun rename(context: Context, tag: String, input: String): String? {
        val others = runCatching { dao(context).allTags() }.getOrDefault(emptyList())
            .filterNot { it == tag }
        val renamed = TagName.canonical(input, others) ?: return null
        if (renamed == tag) return null

        return runCatching {
            dao(context).renameEverywhere(tag, renamed)
            renamed
        }.getOrElse {
            AppLogger.w(TAG, "Could not rename a tag: ${it.message}")
            null
        }
    }

    /**
     * Marks the suggested [tag] on [displayName]'s summary as dismissed, so it is not offered again,
     * including after the summary is written anew (BabApps item 136). Nothing is added to
     * `recording_tags`, and the summary's search text is untouched.
     */
    suspend fun dismissSuggestion(context: Context, displayName: String, tag: String) {
        runCatching {
            val summaries = TranscriptDatabase.get(context).summaryDao()
            val entry = summaries.summary(displayName) ?: return
            val parsed = CallSummary.parse(entry.document) ?: return
            summaries.upsert(entry.copy(document = parsed.withDismissed(tag).toJson()))
        }.onFailure { AppLogger.w(TAG, "Could not dismiss a suggested tag: ${it.message}") }
    }

    /** Removes [tag] from every recording carrying it. */
    suspend fun deleteEverywhere(context: Context, tag: String) {
        runCatching { dao(context).deleteEverywhere(tag) }
            .onFailure { AppLogger.w(TAG, "Could not delete a tag: ${it.message}") }
    }

    /** Takes [tag] off [displayName]. Leaves it on every other recording that carries it. */
    suspend fun remove(context: Context, displayName: String, tag: String) {
        runCatching { dao(context).remove(displayName, tag) }
            .onFailure { AppLogger.w(TAG, "Could not remove a tag: ${it.message}") }
    }
}
