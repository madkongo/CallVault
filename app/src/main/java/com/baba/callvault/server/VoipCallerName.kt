/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import com.baba.callvault.utils.AppLogger

/**
 * Best-effort "who was on the call?" for naming a VoIP recording.
 *
 * Notifications are used here for a reason, and only here: a VoIP call has no number and no call-log
 * entry, the app's own call history sits in a private data directory unreadable even to shell, and
 * Telecom holds nothing because these apps do not register connections (verified on-device — a live
 * Telegram call produced no Telecom entry whatsoever). The ongoing-call notification is the only place
 * the system holds the contact's name. Short of root, that is the boundary.
 *
 * Which *app* is on the call is NOT read from here — see [VoipAppIdentity], which takes it from the
 * audio stream being recorded. This lookup is always scoped to that already-known package, so a name
 * can no longer be paired with the wrong app.
 *
 * Text scraping, so formats vary by app, locale and version: every failure path returns null and the
 * recording is named by time alone. Nothing depends on it.
 */
internal object VoipCallerName {
    /** The shell, by absolute path — see [VoipAppIdentity]. */
    private const val SHELL = "/system/bin/sh"

    private const val TAG = "CV:VoipName"

    /**
     * How long one dump may take. 1.5 s was the intent, but the old read never honoured it, so a slow healthy phone
     * (the whole-dump fallback is megabytes) always got its answer eventually; a bound that bites at 1.5 s would turn
     * those into "no notification". This is a wedge ceiling, not a speed target.
     */
    private const val DUMP_TIMEOUT_MS = 10_000L
    private const val MAX_NAME_LENGTH = 40

    /** Characters unsafe or annoying in a filename, plus anything non-printable. */
    private val UNSAFE = Regex("""[/\\:*?"<>|\p{Cntrl}]""")

    // `String` or `SpannableString`: a title that was Spanned on the app side crosses Binder as the
    // latter and dumps as such (TextUtils.CHAR_SEQUENCE_CREATOR). Seen on the OP9 for other apps.
    private val TITLE_REGEX = Regex("""android\.title=(?:Spannable)?String \((.+?)\)""")
    private val TEXT_REGEX = Regex("""android\.text=(?:Spannable)?String \((.+?)\)""")

    /** Android 14 and earlier print the flags as a number; 15+ as words. Both are checked. */
    private val HEX_FLAGS_REGEX = Regex("""flags=0x([0-9a-fA-F]+)""")
    private val CHRONOMETER_REGEX = Regex("""android\.showChronometer=Boolean \((true|false)\)""")
    private const val FLAG_ONGOING_EVENT = 0x2

    /**
     * The name shown on [packageName]'s ongoing-call notification, or null.
     *
     * Blocking (spawns `dumpsys`), so keep it off the critical path — it runs once per call.
     */
    fun resolve(packageName: String): String? = runCatching {
        // One record at a time through `cmd notification get`, unredacted and a few KB each, rather
        // than the whole multi-megabyte dump under a timeout. The dump stays as the fallback for a
        // ROM whose shell command answers differently.
        val dump = readPackageRecords(packageName) ?: readNotificationDump() ?: return null
        extractFromDump(dump, packageName)
    }.onFailure { AppLogger.d(TAG, "Caller lookup failed: ${it.message}") }.getOrNull()

    /**
     * Whether the call behind [packageName]'s ongoing notification has been picked up. The two
     * "does not say" cases are kept apart: a notification that is not there yet may still come (they
     * post a moment after the audio), one that is there without a timer never will.
     */
    enum class AnswerState { ANSWERED, RINGING, NO_TIMER, NO_NOTIFICATION }

    /**
     * Whether [packageName]'s call is answered, read from the call timer on its notification.
     *
     * Measured 2026-09-22 on the OP9 (WhatsApp): through the ringing the ongoing notification says
     * "Ringing…" with `android.showChronometer=Boolean (false)`; at the pickup it becomes "Ongoing
     * voice call" with the flag true. The flag is the platform's (`EXTRA_SHOW_CHRONOMETER`), so it
     * is read instead of the text, which belongs to the app and the locale. Blocking, like [resolve].
     */
    fun answerState(packageName: String): AnswerState = runCatching {
        val dump = readPackageRecords(packageName) ?: readNotificationDump() ?: return AnswerState.NO_NOTIFICATION
        extractAnswerState(dump, packageName)
    }.onFailure { AppLogger.d(TAG, "Answer lookup failed: ${it.message}") }.getOrDefault(AnswerState.NO_NOTIFICATION)

    /**
     * [AnswerState] from [packageName]'s ongoing record that is marked as a call (category=call, a CallStyle type
     * or an incoming flag) and carries the timer flag; [AnswerState.NO_TIMER] when it has ongoing records but none
     * of them is such a record, [AnswerState.NO_NOTIFICATION] when it has no ongoing record at all.
     *
     * Only the call's own record is read (review C49 and its rework): NotificationCompat writes
     * `showChronometer=false` on every notification it builds, so an ongoing backup or upload notice of the same
     * app read as RINGING, and the hold then deleted an answered short call as unanswered. An app whose call notice
     * carries no call marker (Telegram) reads NO_TIMER: the hold releases at once, never inferred from another notice.
     */
    internal fun extractAnswerState(dump: String, packageName: String): AnswerState {
        if (packageName.isBlank()) return AnswerState.NO_NOTIFICATION
        var ongoingSeen = false
        for (record in dump.split("NotificationRecord(")) {
            if (!record.contains("pkg=$packageName ")) continue
            if (!isOngoing(record)) continue
            ongoingSeen = true
            if (!isMarkedAsCall(record)) continue
            val running = CHRONOMETER_REGEX.find(record)?.groupValues?.get(1) ?: continue
            return answerFrom(running)
        }
        return if (ongoingSeen) AnswerState.NO_TIMER else AnswerState.NO_NOTIFICATION
    }

    private fun answerFrom(running: String): AnswerState =
        if (running == "true") AnswerState.ANSWERED else AnswerState.RINGING

    /** The record says it is a call: the call category, a CallStyle type, or the app's own incoming flag. */
    private fun isMarkedAsCall(record: String): Boolean =
        CATEGORY_REGEX.find(record)?.groupValues?.get(1) == "call" ||
            CALL_TYPE_REGEX.containsMatchIn(record) || INCOMING_FLAG_REGEX.containsMatchIn(record)

    private val CATEGORY_REGEX = Regex("""\bcategory=(\w+)""")
    private val ACTIONS_REGEX = Regex("""\bactions=(\d+)""")
    private val CALL_TYPE_REGEX = Regex("""android\.callType=(?:Integer \()?(\d+)""")
    private val INCOMING_FLAG_REGEX = Regex("""IS_INCOMING_CALL=(?:Boolean \()?(true|false)""")

    /**
     * The structure of [packageName]'s call notification (no text), for the in/out badge (BabApps
     * item 113); null when it has none. Blocking, like [resolve].
     */
    fun callFacts(packageName: String): VoipCallFacts? = runCatching {
        val dump = readPackageRecords(packageName) ?: readNotificationDump() ?: return null
        extractFacts(dump, packageName)
    }.onFailure { AppLogger.d(TAG, "Call facts lookup failed: ${it.message}") }.getOrNull()

    /**
     * Facts of [packageName]'s call-like record: one that carries a CallStyle type, the call category, an
     * incoming flag, or is ongoing with a call timer flag. An ongoing one first (review C30): an older missed-call
     * notice of the app (not ongoing, call category, two actions) read as the incoming marker of the call placed
     * now. A chat notification of the same app is none of those. Telegram's ongoing call sets none of them and so
     * reads null.
     */
    internal fun extractFacts(dump: String, packageName: String): VoipCallFacts? {
        if (packageName.isBlank()) return null
        val all = callLikeFacts(dump, packageName)
        return all.firstOrNull { it.ongoing } ?: all.firstOrNull()
    }

    private fun callLikeFacts(dump: String, packageName: String): List<VoipCallFacts> {
        val found = ArrayList<VoipCallFacts>()
        for (record in dump.split("NotificationRecord(")) {
            if (!record.contains("pkg=$packageName ")) continue
            val category = CATEGORY_REGEX.find(record)?.groupValues?.get(1)
            val callType = CALL_TYPE_REGEX.find(record)?.groupValues?.get(1)?.toIntOrNull()
            val incomingFlag = INCOMING_FLAG_REGEX.find(record)?.groupValues?.get(1)?.toBooleanStrictOrNull()
            val chrono = CHRONOMETER_REGEX.find(record)?.groupValues?.get(1)?.toBooleanStrictOrNull()
            val ongoing = isOngoing(record)
            val callLike = category == "call" || callType != null || incomingFlag != null || (ongoing && chrono != null)
            if (!callLike) continue
            found += VoipCallFacts(
                category = category,
                actionCount = ACTIONS_REGEX.find(record)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                callType = callType,
                showChronometer = chrono,
                ongoing = ongoing,
                incomingFlag = incomingFlag,
            )
        }
        return found
    }

    /**
     * Finds [packageName]'s ongoing notification and reads the contact from it.
     *
     * Two hard-won details. Records are split on `NotificationRecord(` so every field stays with its
     * owner. And the contact is NOT always in the title: WhatsApp titles the notification with the
     * person, while Telegram titles it "Ongoing Telegram call" and puts the person in `android.text`.
     * So both fields are candidates, in that order, and one that merely restates the app is skipped.
     *
     * Matching does not filter on `category=call`: Telegram's call notification sets no category at
     * all. Scoping to the package makes that filter unnecessary anyway — an ongoing notification from
     * the app whose call audio we are recording is the call.
     */
    internal fun extractFromDump(dump: String, packageName: String): String? {
        if (packageName.isBlank()) return null
        // The platform's own ongoing notifications (battery, USB, screen recording...) are never a
        // caller. Defence in depth: the app already refuses such a uid (BabApps item 103).
        if (packageName == "com.android.systemui" || packageName == "android") return null

        for (record in dump.split("NotificationRecord(")) {
            if (!record.contains("pkg=$packageName ")) continue
            // Call notifications are ongoing; this skips the app's chat and message notifications.
            if (!isOngoing(record)) continue

            val candidates = listOfNotNull(
                TITLE_REGEX.find(record)?.groupValues?.get(1),
                TEXT_REGEX.find(record)?.groupValues?.get(1),
            )
            val name = candidates.firstNotNullOfOrNull { sanitize(it, packageName) }
            if (name != null) {
                AppLogger.i(TAG, "Caller name resolved for $packageName")   // the name is never logged
                return name
            }
        }
        return null
    }

    /**
     * Trims a candidate to something usable as a filename, or null.
     *
     * Rejects anything that merely restates the app: Telegram's title is "Ongoing Telegram call", a
     * status line rather than a person, and that string in a filename is worse than no name at all.
     */
    /**
     * Whether the record's flags carry ONGOING_EVENT — as the word Android 15+ prints, or as the 0x2
     * bit in the hex Android 14 and earlier print (`flags=0x62`). Measured 2026-09-22: the OP9
     * (Android 14) dump held the word nowhere and the hex 868 times, so the word alone had rejected
     * every WhatsApp call on that phone — 1 of 6 named, against 18 of 18 on the Android 16 OP12.
     */
    internal fun isOngoing(record: String): Boolean {
        if (record.contains("ONGOING_EVENT")) return true
        val hex = HEX_FLAGS_REGEX.find(record)?.groupValues?.get(1) ?: return false
        return (hex.toLongOrNull(16) ?: return false) and FLAG_ONGOING_EVENT.toLong() != 0L
    }

    /** The keys in a `cmd notification list` output that belong to [packageName]: `user|pkg|id|tag|uid`. */
    internal fun keysFor(list: String, packageName: String): List<String> =
        list.lineSequence().map { it.trim() }.filter { it.split('|').getOrNull(1) == packageName }.toList()

    private fun sanitize(raw: String, packageName: String): String? {
        val cleaned = UNSAFE.replace(raw, "").trim().trimEnd('.')
        if (cleaned.isEmpty()) return null

        // "org.telegram.messenger" -> "telegram"; a candidate containing it describes the app, not a person.
        val appToken = packageName.split('.')
            .filter { it.length > 3 && it !in GENERIC_PACKAGE_PARTS }
            .maxByOrNull { it.length }
        if (appToken != null && cleaned.contains(appToken, ignoreCase = true)) {
            AppLogger.d(TAG, "Ignoring notification text that just names the app")
            return null
        }
        // A Telegram fork's package has no "telegram" in it (com.radolyn.ayugram), so the token above
        // misses its status line "Telegram call". Only for Telegram clients: a WhatsApp contact called
        // "Telegram support" is a person and stays (BabApps item 107).
        if (isTelegramClient(packageName) && cleaned.contains("telegram", ignoreCase = true)) {
            AppLogger.d(TAG, "Ignoring notification text that just names the app")
            return null
        }
        return if (cleaned.length > MAX_NAME_LENGTH) cleaned.take(MAX_NAME_LENGTH).trim() else cleaned
    }

    /** Telegram clients that title their call "Telegram call": the official app, org.telegram.* builds and known forks. */
    internal fun isTelegramClient(packageName: String): Boolean =
        packageName.startsWith("org.telegram.") || packageName in TELEGRAM_FORK_PACKAGES

    private val TELEGRAM_FORK_PACKAGES = setOf("com.radolyn.ayugram", "tw.nekomimi.nekogram")

    private val GENERIC_PACKAGE_PARTS = setOf("com", "org", "net", "android", "messenger", "app", "mobile")

    /**
     * [packageName]'s notification records, each from `cmd notification get <key>`, joined; null when
     * the list command is unavailable or lists nothing for the package — the caller then falls back to
     * the whole dump, which answers the same question more slowly.
     */
    private fun readPackageRecords(packageName: String): String? {
        val list = shell("cmd notification list") ?: return null
        val keys = keysFor(list, packageName)
        if (keys.isEmpty()) return null
        return keys.mapNotNull { key -> shell("cmd notification get '${key.replace("'", "")}'") }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
    }

    private fun readNotificationDump(): String? = shell("dumpsys notification --noredact")

    internal fun shell(command: String, shellPath: String = SHELL, timeoutMs: Long = DUMP_TIMEOUT_MS): String? =
        // Absolute path — see VoipAppIdentity. resolve() already returns null on any failure, so a
        // missing shell costs the caller name and nothing else. Bounded for real (C67).
        BoundedProcess.runText(arrayOf(shellPath, "-c", command), timeoutMs, TAG)
}
