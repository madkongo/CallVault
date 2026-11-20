/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.phone

import com.baba.callvault.data.PrivilegedMode

/** A fact the phone either confirmed or did not: never a guess. */
enum class Tri {
    YES, NO, UNKNOWN;

    val label: String get() = when (this) { YES -> "yes"; NO -> "no"; UNKNOWN -> "?" }

    companion object {
        fun of(value: Boolean?): Tri = when (value) { true -> YES; false -> NO; null -> UNKNOWN }
    }
}

/**
 * The app-standby bucket, collapsed to what matters. Only RESTRICTED blocks background starts; RARE defers jobs.
 * Collapsing keeps the always-on log from changing every time Android moves the app between ACTIVE and FREQUENT.
 */
enum class StandbyBucketClass {
    NORMAL, RARE, RESTRICTED, UNKNOWN;

    val label: String get() = if (this == UNKNOWN) "?" else name.lowercase()

    companion object {
        // UsageStatsManager.STANDBY_BUCKET_* values, spelled out so this stays a pure function.
        private const val BUCKET_RARE = 40
        private const val BUCKET_RESTRICTED = 45

        fun of(bucket: Int?): StandbyBucketClass = when {
            bucket == null -> UNKNOWN
            bucket >= BUCKET_RESTRICTED -> RESTRICTED
            bucket >= BUCKET_RARE -> RARE
            else -> NORMAL
        }
    }
}

/** ROM families we can tell apart by a system property. Used for wording and the report, never for a code path. */
enum class RomFamily { ONE_UI, COLOROS, HYPEROS, MIUI, MAGICOS, EMUI, VIVO, UNKNOWN }

data class Rom(val family: RomFamily, val version: String?)

/**
 * Everything the app can read about the phone, cheaply and without writing anything.
 *
 * A pure snapshot: built by [PhoneProfileReader], evaluated by [PhoneRules]. Anything that could not be read is
 * null or [Tri.UNKNOWN]; an unknown fact never activates a rule. No phone number, account or app list.
 */
data class PhoneProfile(
    val maker: String,
    val model: String,
    val rom: Rom,
    val sdk: Int,
    val mode: PrivilegedMode?,
    val phoneCallsOn: Tri,
    val appCallsOn: Tri,
    val batteryExempt: Tri,
    val backgroundRestricted: Tri,
    val standbyBucket: StandbyBucketClass,
    val hibernation: Tri,
    val notificationsEnabled: Tri,
    /** Notification channels the user blocked, by id. Empty when none or unreadable. */
    val blockedChannels: List<String>,
    /** The Default USB Configuration as last cached by the app (UNKNOWN name when never read). */
    val usbDefault: String,
) {
    /** One line, no personal data; the always-on log and the report both use it. */
    fun logLine(): String =
        "maker=$maker model=$model rom=${rom.family.name.lowercase()}${rom.version?.let { " $it" } ?: ""} sdk=$sdk " +
            "mode=${mode?.key ?: "?"} calls=${phoneCallsOn.label} appcalls=${appCallsOn.label} " +
            "exempt=${batteryExempt.label} restricted=${backgroundRestricted.label} bucket=${standbyBucket.label} " +
            "hib=${hibernation.label} notif=${notificationsEnabled.label} " +
            "blockedch=${blockedChannels.joinToString(",").ifEmpty { "-" }} usb=${usbDefault}"

    /** Short stable id of [logLine]: the log is written only when this changes. */
    fun hash(): String = Integer.toHexString(logLine().hashCode())

    companion object {
        val UNKNOWN = PhoneProfile(
            maker = "?", model = "?", rom = Rom(RomFamily.UNKNOWN, null), sdk = 0, mode = null,
            phoneCallsOn = Tri.UNKNOWN, appCallsOn = Tri.UNKNOWN, batteryExempt = Tri.UNKNOWN,
            backgroundRestricted = Tri.UNKNOWN, standbyBucket = StandbyBucketClass.UNKNOWN,
            hibernation = Tri.UNKNOWN, notificationsEnabled = Tri.UNKNOWN, blockedChannels = emptyList(),
            usbDefault = "?",
        )
    }
}
