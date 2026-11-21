/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

/**
 * Contact names remembered across Home list passes, so a resume does not ask the contacts provider
 * about every distinct number again (R46). Every answer is tied to the contacts version it was read
 * under ([ContactsChanges.version]): when a contact changes the whole cache is dropped, so a new or
 * renamed contact shows on the next pass, as it did when every pass looked everything up.
 *
 * "No contact" is remembered as "". A lookup that throws is NOT remembered (it propagates), so a
 * provider hiccup cannot become a permanent "no name".
 */
internal class ContactNameCache(private val maxEntries: Int = 4096) {

    private val map = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > maxEntries
    }
    private var seenVersion = Long.MIN_VALUE

    /** The name for [key] ("" for none), reading it with [lookup] (outside the lock) when not cached for [version]. */
    fun resolve(key: String, version: Long, lookup: () -> String?): String {
        synchronized(map) {
            if (version != seenVersion) {
                map.clear()
                seenVersion = version
            }
            map[key]?.let { return it }
        }
        val found = lookup() ?: ""
        synchronized(map) {
            if (version == seenVersion) map[key] = found
        }
        return found
    }

    fun size(): Int = synchronized(map) { map.size }
}

/**
 * Recordings whose length could not be read, so Home does not open them again on every list pass (R46).
 * A mark is tied to the file's size and modified time and expires after [retryAfterMs], so a file that
 * changed, or a transient failure, is tried again. In memory only: a restart tries every file once more.
 */
internal class FailedProbeMarks(private val retryAfterMs: Long = 30 * 60_000L, private val maxEntries: Int = 4096) {

    private class Mark(val size: Long, val modified: Long, val atMs: Long)

    private val marks = object : LinkedHashMap<String, Mark>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Mark>?): Boolean = size > maxEntries
    }

    fun shouldSkip(name: String, size: Long, modified: Long, nowMs: Long): Boolean = synchronized(marks) {
        val m = marks[name] ?: return false
        m.size == size && m.modified == modified && nowMs - m.atMs < retryAfterMs
    }

    /** Remembers the failure; true when it is new (not already marked for this size and modified time), for logging once. */
    fun markFailed(name: String, size: Long, modified: Long, nowMs: Long): Boolean = synchronized(marks) {
        val m = marks[name]
        val isNew = m == null || m.size != size || m.modified != modified || nowMs - m.atMs >= retryAfterMs
        if (isNew) marks[name] = Mark(size, modified, nowMs)
        isNew
    }

    fun clear(name: String) {
        synchronized(marks) { marks.remove(name) }
    }
}
