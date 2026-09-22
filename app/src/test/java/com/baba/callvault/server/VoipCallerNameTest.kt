/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers reading the contact's name off an app's ongoing-call notification.
 *
 * Three real on-device failures drive these. A Telegram call was labelled "WhatsApp", then "Google",
 * because the lookup searched ALL notifications for one tagged `category=call` — and Telegram's call
 * notification sets no category at all, so an unrelated app's notification won. And the contact was
 * read only from `android.title`, which for Telegram holds the status line "Ongoing Telegram call"
 * while the person's name sits in `android.text`.
 *
 * The overriding rule is the failure mode: anything unexpected yields null so the recording is named
 * by time alone, never with a wrong name.
 */
class VoipCallerNameTest {

    /** Shaped after a real `dumpsys notification --noredact` record. */
    private fun record(
        pkg: String,
        title: String,
        text: String = "tap to return to the call",
        ongoing: Boolean = true,
        category: String? = null,
        chronometer: Boolean? = null,
    ) = """
        NotificationRecord(0x01: pkg=$pkg user=UserHandle{0} id=201 tag=null
          Notification(channel=Other flags=${if (ongoing) "ONGOING_EVENT|NO_CLEAR|FOREGROUND_SERVICE" else "0"})
          ${category?.let { "category=$it" } ?: ""}
          extras={
                android.title=String ($title)
                android.text=String ($text)
                ${chronometer?.let { "android.showChronometer=Boolean ($it)" } ?: ""}
          }
    """.trimIndent()

    // --- "Start when they answer" for app calls: the call timer on the notification -----------------
    //
    // Measured 2026-09-22 on the OP9 (WhatsApp 2.26.36.74): through 13 s of ringing the ongoing
    // notification said "Ringing…" with android.showChronometer=Boolean (false); at the pickup it
    // became "Ongoing voice call" with showChronometer=Boolean (true). The flag is the platform's, so
    // it is read rather than the text, which is the app's and the locale's.

    @Test
    fun `a call notification whose timer is not running is still ringing`() {
        val dump = record("com.whatsapp", "Feroza", text = "Ringing…", chronometer = false)
        assertEquals(VoipCallerName.AnswerState.RINGING, VoipCallerName.extractAnswerState(dump, "com.whatsapp"))
    }

    @Test
    fun `a call notification with a running timer is answered`() {
        val dump = record("com.whatsapp", "Feroza", text = "Ongoing voice call", chronometer = true)
        assertEquals(VoipCallerName.AnswerState.ANSWERED, VoipCallerName.extractAnswerState(dump, "com.whatsapp"))
    }

    @Test
    fun `an ongoing notification without the timer flag means this app never says`() {
        val dump = record("org.telegram.messenger", "Ongoing Telegram call", text = "Feroza")
        assertEquals(VoipCallerName.AnswerState.NO_TIMER, VoipCallerName.extractAnswerState(dump, "org.telegram.messenger"))
    }

    @Test
    fun `no ongoing notification for the package is a different answer from no timer`() {
        // Not posted yet (they follow the audio by a moment) or already gone (WhatsApp removes it at
        // hang-up BEFORE the audio mode drops — measured 2026-09-22, an unanswered call published a
        // 6 KB stub because this read as "does not say" and released the hold).
        assertEquals(VoipCallerName.AnswerState.NO_NOTIFICATION, VoipCallerName.extractAnswerState("", "com.whatsapp"))
        val chat = record("com.whatsapp", "Feroza", text = "hi", ongoing = false, chronometer = true)
        assertEquals(VoipCallerName.AnswerState.NO_NOTIFICATION, VoipCallerName.extractAnswerState(chat, "com.whatsapp"))
    }

    @Test
    fun `the timer flag of another app's notification is not read`() {
        val other = record("org.telegram.messenger", "Ongoing Telegram call", chronometer = true)
        assertEquals(VoipCallerName.AnswerState.NO_NOTIFICATION, VoipCallerName.extractAnswerState(other, "com.whatsapp"))
    }

    @Test
    fun `reads the caller from the title when the app puts it there`() {
        // WhatsApp's shape: the person is the title.
        val name = VoipCallerName.extractFromDump(record("com.whatsapp", "Feroza"), "com.whatsapp")
        assertEquals("Feroza", name)
    }

    @Test
    fun `falls back to the text when the title only restates the app`() {
        // Telegram's shape, verified on-device: title is a status line, android.text is the person.
        val dump = record("org.telegram.messenger", "Ongoing Telegram call", text = "Feroza")
        assertEquals("Feroza", VoipCallerName.extractFromDump(dump, "org.telegram.messenger"))
    }

    @Test
    fun `finds a call notification that carries no category at all`() {
        // The regression: Telegram sets no category, so a category=call filter never matched it.
        val dump = record("org.telegram.messenger", "Ongoing Telegram call", text = "Alex")
        assertEquals("Alex", VoipCallerName.extractFromDump(dump, "org.telegram.messenger"))
    }

    @Test
    fun `ignores other apps' notifications entirely`() {
        // The regression that produced "_voip_Google.ogg": another app's notification won the race.
        val dump = record("com.google.android.googlequicksearchbox", "Weather", category = "call") +
            "\n" + record("org.telegram.messenger", "Ongoing Telegram call", text = "Alex")

        assertEquals("Alex", VoipCallerName.extractFromDump(dump, "org.telegram.messenger"))
        assertNull(VoipCallerName.extractFromDump(dump, "com.whatsapp"))
    }

    @Test
    fun `ignores the app's own non-ongoing notifications`() {
        // A chat message from the same app must not be mistaken for the call.
        val dump = record("org.telegram.messenger", "Bob", text = "see you at 5", ongoing = false)
        assertNull(VoipCallerName.extractFromDump(dump, "org.telegram.messenger"))
    }

    @Test
    fun `returns null when neither field names a person`() {
        val dump = record("org.telegram.messenger", "Ongoing Telegram call", text = "Telegram")
        assertNull(VoipCallerName.extractFromDump(dump, "org.telegram.messenger"))
    }

    @Test
    fun `strips characters that would break a filename`() {
        assertEquals("AaBb", VoipCallerName.extractFromDump(record("com.whatsapp", "A/a:B*b"), "com.whatsapp"))
    }

    @Test
    fun `keeps non-latin names intact`() {
        assertEquals("גבריאל", VoipCallerName.extractFromDump(record("com.whatsapp", "גבריאל"), "com.whatsapp"))
    }

    @Test
    fun `truncates an absurdly long name`() {
        val name = VoipCallerName.extractFromDump(record("com.whatsapp", "N".repeat(200)), "com.whatsapp")
        assertEquals(40, name?.length)
    }

    @Test
    fun `falls through to the text when the title is only unusable characters`() {
        assertEquals("Dana", VoipCallerName.extractFromDump(record("com.whatsapp", "///", text = "Dana"), "com.whatsapp"))
    }

    @Test
    fun `returns null for empty or junk input rather than throwing`() {
        assertNull(VoipCallerName.extractFromDump("", "com.whatsapp"))
        assertNull(VoipCallerName.extractFromDump("not a dump at all", "com.whatsapp"))
    }

    @Test
    fun `returns null when the package is unknown`() {
        assertNull(VoipCallerName.extractFromDump(record("com.whatsapp", "Feroza"), ""))
    }

    // ---- Android 14, where the flags are hex and the word ONGOING_EVENT never appears ----

    /**
     * Shaped after a real `cmd notification get` record on the OP9 (Android 14, 2026-09-22): the flags
     * are printed as `flags=0x62` — `Notification.flagsToString` exists only from Android 15. Every
     * WhatsApp call on that phone was nameless because of it: 1 of 6 named, versus 18 of 18 on Android 16.
     */
    private fun android14Record(pkg: String, title: String, text: String, flagsHex: String, titleClass: String = "String") = """
        NotificationRecord(0x08230341: pkg=$pkg user=UserHandle{0} id=201 tag=null importance=4 key=0|$pkg|201|null|10135: Notification(channel=voip_notification flags=0x$flagsHex color=0xff25d366 vis=PRIVATE))
          icon=Icon(typ=RESOURCE pkg=$pkg id=0x7f081bab)
          flags=0x$flagsHex
                android.title=null
                android.title=$titleClass ($title)
                android.text=String ($text)
    """.trimIndent()

    @Test
    fun `an android 14 record with the ongoing bit set in hex flags is a call`() {
        val dump = android14Record("com.whatsapp", "Feroza", "Ongoing voice call", flagsHex = "62")

        assertEquals("Feroza", VoipCallerName.extractFromDump(dump, "com.whatsapp"))
    }

    @Test
    fun `an android 14 record without the ongoing bit is not a call`() {
        // 0x200 = LOCAL_ONLY-style flags of an ordinary message notification: no 0x2 bit.
        val dump = android14Record("com.whatsapp", "Feroza", "new message", flagsHex = "200")

        assertNull(VoipCallerName.extractFromDump(dump, "com.whatsapp"))
    }

    @Test
    fun `a title that crossed binder as a spannable string is still a title`() {
        // Any Spanned title arrives as SpannableString and dumps as such; seen on the OP9 for other apps.
        val dump = android14Record("com.whatsapp", "Feroza", "Ongoing voice call", flagsHex = "62", titleClass = "SpannableString")

        assertEquals("Feroza", VoipCallerName.extractFromDump(dump, "com.whatsapp"))
    }

    @Test
    fun `the keys of one package are picked out of the shell's notification list`() {
        val list = """
            0|com.google.android.googlequicksearchbox|0|1524207345::SUMMARY::wx|10135
            0|com.whatsapp|201|null|10135
            0|com.whatsapp|1|null|10135
            0|org.telegram.messenger|2|null|10304
        """.trimIndent()

        assertEquals(listOf("0|com.whatsapp|201|null|10135", "0|com.whatsapp|1|null|10135"), VoipCallerName.keysFor(list, "com.whatsapp"))
    }
}
