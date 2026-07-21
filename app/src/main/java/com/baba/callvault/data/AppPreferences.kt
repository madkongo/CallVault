/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioSource
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.data.SyncScheduleMode

/**
 * AppPreferences wraps [android.content.SharedPreferences] to provide typed access to all
 * user-configurable settings stored on the device.
 */
class AppPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "callvault_prefs"

        /** Public key id for the available-update tag, for change-listener comparisons. */
        const val AVAILABLE_UPDATE_TAG_KEY = "available_update_tag"
    }

    /**
     * Single source of truth for all default settings values.
     * These value are the default app settings.
     */
    object DefaultsValue {
        // --- Onboarding & Legal ---
        const val DISCLAIMER_ACCEPTED = false
        // Whether the one-time post-onboarding setup wizard has been completed. Default false so the
        // wizard is shown once after permissions are granted, before the user reaches Home.
        const val WIZARD_COMPLETED = false

        // --- Storage & General ---
        val RECORDING_FOLDER_URI: String? = null
        const val VIBRATION_ENABLED = true
        
        // --- Automation ---
        const val AUTO_RECORD_INCOMING = false
        const val AUTO_RECORD_OUTGOING = false
        
        // --- Filters & Contacts ---
        const val IGNORE_ANONYMOUS_INCOMING = false
        const val IGNORE_CROSS_COUNTRY_INCOMING = false
        const val IGNORE_CROSS_COUNTRY_OUTGOING = false
        val IGNORE_CONTACTS_MODE_INCOMING = IgnoreContactsMode.NONE
        val IGNORE_CONTACTS_MODE_OUTGOING = IgnoreContactsMode.NONE
        val IGNORED_CONTACTS_INCOMING = emptySet<String>()
        val IGNORED_CONTACTS_OUTGOING = emptySet<String>()
        
        // --- Developer & Debug ---
        const val LOGGING_ENABLED = false
        const val DEBUG_ENABLED = false
        const val DEBUG_CALLER_NUMBER = ""
        const val DEVELOPER_MODE_UNLOCKED = false
        
        // --- Storage Routing ---
        val STORAGE_TARGET = StorageTarget.LOCAL.key
        val DRIVE_FOLDER_URI: String? = null

        // --- Sync Schedule (cloud copy cadence) ---
        // IMMEDIATE (default) keeps the per-recording copy behaviour; DAILY/WEEKLY batch the copy via a
        // periodic sweep at SYNC_TIME_HOUR:SYNC_TIME_MINUTE (WEEKLY also on SYNC_DAY_OF_WEEK).
        val SYNC_SCHEDULE_MODE = SyncScheduleMode.IMMEDIATE.key
        const val SYNC_TIME_HOUR = 2          // 0-23
        const val SYNC_TIME_MINUTE = 0        // 0-59
        const val SYNC_DAY_OF_WEEK = 2        // java.util.Calendar: SUNDAY=1..SATURDAY=7 (2 = Monday)

        // --- Retention (auto-delete old recordings) ---
        // Delete recordings older than N days. 0 = keep forever (OFF). Applied per copy: device copies
        // use RETENTION_LOCAL_DAYS, Drive copies use RETENTION_DRIVE_DAYS. When RETENTION_LINKED is true
        // the UI shows one selector that writes both to the same value. Defaults to OFF so nothing is
        // ever deleted until the user explicitly opts in.
        const val RETENTION_LINKED = true
        const val RETENTION_LOCAL_DAYS = 0
        const val RETENTION_DRIVE_DAYS = 0
        // Daily sweep time, in the device's LOCAL time zone (so e.g. "00:00" means local midnight
        // wherever the user is). The schedule is re-anchored to local time on app start and on a
        // time-zone change.
        const val RETENTION_TIME_HOUR = 3      // 0-23
        const val RETENTION_TIME_MINUTE = 30   // 0-59

        // --- ADB (embedded wireless-debugging transport) ---
        // Whether the user has completed the one-time ADB pairing. Persisted so onboarding is
        // not shown again on every launch (a live connection only exists per-process).
        const val ADB_PAIRED = false

        // --- In-app updates ---
        // Check for new GitHub releases (a tiny daily JSON query). ON by default; the APK download
        // itself only ever happens silently on unmetered networks or on an explicit user tap.
        const val UPDATE_CHECK_ENABLED = true
        // Install updates silently through the privileged shell. OFF by default — opt-in.
        const val AUTO_UPDATE_ENABLED = false

        // --- Persistent recorder server (CallVault Plan 5) ---
        // OFF by default: when false the existing local recording path runs unchanged. When true the
        // recording layer drives the detached privileged daemon (RecorderServer) over binder instead.
        const val PERSISTENT_SERVER_ENABLED = false

        // WD policy for persistent-server mode. false (default) = keep Wireless debugging ON (safer,
        // matches the legacy behaviour). true = turn WD OFF once the daemon's binder is connected and
        // re-enable it only transiently to (re)launch the daemon — the persistent-server payoff.
        const val WD_DISABLE_WHEN_IDLE = false

        // --- Audio/Scrcpy Quality ---
        val AUDIO_SOURCE = ScrcpyAudioSource.VOICE_CALL.cliKey
        val AUDIO_CODEC = ScrcpyAudioCodec.OPUS.cliKey

        val AUDIO_BITRATE = ScrcpyAudioCodec.OPUS.defaultBitRate

        // --- File Naming ---
        const val FILE_NAME_TEMPLATE = "{date}_{direction}_{contact_name}"

        // --- UI & Appearance ---
        val THEME_MODE = ThemeMode.SYSTEM
        const val DYNAMIC_COLOR = false // Signal brand colors are the identity; Material You is opt-in
        const val SHOW_TOASTS = true
    }

    /**
     * Enum containing all SharedPreferences keys to prevent string typos.
     * Add new keys here when adding new settings.
     */
    enum class Key(val id: String) {
        // --- Onboarding & Legal ---
        DISCLAIMER_ACCEPTED("disclaimer_accepted"),
        WIZARD_COMPLETED("wizard_completed"),

        // --- Storage & General ---
        RECORDING_FOLDER_URI("recording_folder_uri"),
        VIBRATION_ENABLED("vibration_enabled"),

        // --- Storage Routing ---
        STORAGE_TARGET("storage_target"),
        DRIVE_FOLDER_URI("drive_folder_uri"),

        // --- Sync Schedule ---
        SYNC_SCHEDULE_MODE("sync_schedule_mode"),
        SYNC_TIME_HOUR("sync_time_hour"),
        SYNC_TIME_MINUTE("sync_time_minute"),
        SYNC_DAY_OF_WEEK("sync_day_of_week"),

        // --- Retention ---
        RETENTION_LINKED("retention_linked"),
        RETENTION_LOCAL_DAYS("retention_local_days"),
        RETENTION_DRIVE_DAYS("retention_drive_days"),
        RETENTION_TIME_HOUR("retention_time_hour"),
        RETENTION_TIME_MINUTE("retention_time_minute"),

        // --- ADB ---
        ADB_PAIRED("adb_paired"),

        // --- In-app updates ---
        UPDATE_CHECK_ENABLED("update_check_enabled"),
        AUTO_UPDATE_ENABLED("auto_update_enabled"),
        AVAILABLE_UPDATE_TAG("available_update_tag"),
        PENDING_UPDATE_TAG("pending_update_tag"),
        LAST_NOTIFIED_UPDATE_TAG("last_notified_update_tag"),
        LAST_UPDATE_CHECK_MILLIS("last_update_check_millis"),
        UPDATE_INSTALL_ARMED("update_install_armed"),
        LAST_SEEN_VERSION_CODE("last_seen_version_code"),
        UPDATE_SUCCESS_BANNER_VERSION("update_success_banner_version"),

        // --- Persistent recorder server (CallVault Plan 5) ---
        PERSISTENT_SERVER_ENABLED("persistent_server_enabled"),
        WD_DISABLE_WHEN_IDLE("wd_disable_when_idle"),
        
        // --- Automation ---
        AUTO_RECORD_INCOMING("auto_record_incoming"),
        AUTO_RECORD_OUTGOING("auto_record_outgoing"),
        
        // --- Filters & Contacts ---
        IGNORE_ANONYMOUS_INCOMING("ignore_anonymous_incoming"),
        IGNORE_CROSS_COUNTRY_INCOMING("ignore_cross_country_incoming"),
        IGNORE_CROSS_COUNTRY_OUTGOING("ignore_cross_country_outgoing"),
        IGNORE_CONTACTS_MODE_INCOMING("ignore_contacts_mode_incoming"),
        IGNORE_CONTACTS_MODE_OUTGOING("ignore_contacts_mode_outgoing"),
        IGNORED_CONTACTS_INCOMING("ignored_contacts_incoming"),
        IGNORED_CONTACTS_OUTGOING("ignored_contacts_outgoing"),
        
        // --- Developer & Debug ---
        LOGGING_ENABLED("logging_enabled"),
        DEBUG_ENABLED("debug_enabled"),
        DEBUG_CALLER_NUMBER("debug_caller_number"),
        DEVELOPER_MODE_UNLOCKED("developer_mode_unlocked"),
        
        // --- Audio/Scrcpy Quality ---
        AUDIO_SOURCE("audio_source"),
        AUDIO_CODEC("audio_codec"),
        AUDIO_BITRATE("audio_bitrate"),
        
        // --- File Naming ---
        FILE_NAME_TEMPLATE("file_name_template"),

        // --- UI & Appearance ---
        THEME_MODE("theme_mode"),
        DYNAMIC_COLOR("dynamic_color"),
        SHOW_TOASTS("show_toasts");
    }

    // -------- Nested enums

    /**
     * Controls which contacts are excluded from automatic recording for a given call direction.
     *
     * @param key The lowercase string stored in SharedPreferences.
     */
    enum class IgnoreContactsMode(val key: String) {
        /** Record all contacts; ignore no one. */
        NONE("none"),
        /** Skip recording for all numbers that appear in the device's Contacts. */
        ALL("all"),
        /** Skip recording only for the numbers explicitly added to the ignore list. */
        SELECTED("selected");

        companion object {
            /**
             * Parses a key string back into an enum constant.
             *
             * @throws IllegalArgumentException if no matching entry is found.
             * @param key The string stored in SharedPreferences.
             * @return The matching [IgnoreContactsMode], or throws an error if unrecognized.
             */
            fun fromKey(key: String?): IgnoreContactsMode {
                return entries.firstOrNull { it.key == key } ?: throw IllegalArgumentException("Unknown IgnoreContactsMode key: $key")
            }
        }
    }

    /**
     * Controls the app theme.
     *
     * @param key The lowercase string.
     */
    enum class ThemeMode(val key: String) {
        SYSTEM("system"), LIGHT("light"), DARK("dark");
        companion object {
            /**
             * Parses a key string back into an enum constant.
             *
             * @throws IllegalArgumentException if no matching entry is found.
             * @param key The string stored in SharedPreferences.
             * @return The matching [ThemeMode], or throws an error if unrecognized.
             */
            fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: throw IllegalArgumentException("Unknown ThemeMode key: $key")
        }
    }

    // -------- SharedPreferences instance

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Registers a change listener so callers can react the instant a preference is written from
     * another component (e.g. a background worker setting [AVAILABLE_UPDATE_TAG_KEY]). Callers MUST
     * [unregisterChangeListener] to avoid leaks. The listener receives the changed key id; compare
     * against the public key constants (e.g. [AVAILABLE_UPDATE_TAG_KEY]).
     */
    fun registerChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    // -------- Helpers to simplify reading/writing

    private fun getBoolean(key: Key, default: Boolean = false) = prefs.getBoolean(key.id, default)
    private fun setBoolean(key: Key, value: Boolean) = prefs.edit { putBoolean(key.id, value) }

    private fun getString(key: Key, default: String? = null) = prefs.getString(key.id, default)
    private fun setString(key: Key, value: String?) = prefs.edit { putString(key.id, value) }

    private fun getInt(key: Key, default: Int = 0) = prefs.getInt(key.id, default)
    private fun setInt(key: Key, value: Int) = prefs.edit { putInt(key.id, value) }

    private fun getLong(key: Key, default: Long = 0L) = prefs.getLong(key.id, default)
    private fun setLong(key: Key, value: Long) = prefs.edit { putLong(key.id, value) }

    private fun getStringSet(key: Key, default: Set<String> = emptySet()) = prefs.getStringSet(key.id, default)?.toSet().orEmpty()
    private fun setStringSet(key: Key, value: Set<String>) = prefs.edit { putStringSet(key.id, value) }

    // ==========================================
    // -------- Accessors (By Category) ---------
    // ==========================================

    // -------- Onboarding & Disclaimer --------

    /** Checks if the user has accepted the disclaimer. */
    fun isDisclaimerAccepted() = getBoolean(Key.DISCLAIMER_ACCEPTED, DefaultsValue.DISCLAIMER_ACCEPTED)
    
    /** Sets whether the user has accepted the disclaimer. */
    fun setDisclaimerAccepted(accepted: Boolean) = setBoolean(Key.DISCLAIMER_ACCEPTED, accepted)

    /** Whether the one-time post-onboarding setup wizard has been completed. */
    fun isWizardCompleted() = getBoolean(Key.WIZARD_COMPLETED, DefaultsValue.WIZARD_COMPLETED)

    /** Marks the one-time setup wizard as completed (set on the wizard's Finish step). */
    fun setWizardCompleted(enabled: Boolean) = setBoolean(Key.WIZARD_COMPLETED, enabled)

    /** Whether the one-time ADB wireless-debugging pairing has been completed. */
    fun isAdbPaired() = getBoolean(Key.ADB_PAIRED, DefaultsValue.ADB_PAIRED)

    /** Marks the one-time ADB pairing as completed (set after the first successful connection). */
    fun setAdbPaired(paired: Boolean) = setBoolean(Key.ADB_PAIRED, paired)

    // -------- Persistent Recorder Server (CallVault Plan 5) --------

    /**
     * Whether the persistent privileged recorder daemon path is enabled. OFF by default: when false the
     * existing local recording pipeline runs unchanged; when true the recording layer drives the
     * detached daemon ([com.baba.callvault.server.RecorderServer]) over binder.
     */
    fun isUpdateCheckEnabled() = getBoolean(Key.UPDATE_CHECK_ENABLED, DefaultsValue.UPDATE_CHECK_ENABLED)
    fun setUpdateCheckEnabled(enabled: Boolean) = setBoolean(Key.UPDATE_CHECK_ENABLED, enabled)

    fun isAutoUpdateEnabled() = getBoolean(Key.AUTO_UPDATE_ENABLED, DefaultsValue.AUTO_UPDATE_ENABLED)
    fun setAutoUpdateEnabled(enabled: Boolean) = setBoolean(Key.AUTO_UPDATE_ENABLED, enabled)

    /** Release tag of a known-newer version (drives the Home banner + notification); null = none. */
    fun getAvailableUpdateTag() = getString(Key.AVAILABLE_UPDATE_TAG)
    fun setAvailableUpdateTag(tag: String?) = setString(Key.AVAILABLE_UPDATE_TAG, tag)

    /** Tag of an update whose install was fired but not yet confirmed; used to report success/failure. */
    fun getPendingUpdateTag() = getString(Key.PENDING_UPDATE_TAG)
    fun setPendingUpdateTag(tag: String?) = setString(Key.PENDING_UPDATE_TAG, tag)

    /** Last tag the "update available" notification was posted for, so one tag notifies only once. */
    fun getLastNotifiedUpdateTag() = getString(Key.LAST_NOTIFIED_UPDATE_TAG)
    fun setLastNotifiedUpdateTag(tag: String?) = setString(Key.LAST_NOTIFIED_UPDATE_TAG, tag)

    /** Epoch millis of the last completed update check; throttles the check-on-open trigger. */
    fun getLastUpdateCheckMillis() = getLong(Key.LAST_UPDATE_CHECK_MILLIS)
    fun setLastUpdateCheckMillis(millis: Long) = setLong(Key.LAST_UPDATE_CHECK_MILLIS, millis)

    /**
     * One-shot consent flag: set true the instant the user taps "Update", consumed (cleared) by the
     * install worker when it starts. It gates the install so that a WorkManager re-run of an
     * interrupted install (process death mid-install) does NOT silently reinstall without the user
     * tapping again — the worker no-ops when this is false and the banner reappears for a fresh tap.
     */
    fun isUpdateInstallArmed() = getBoolean(Key.UPDATE_INSTALL_ARMED)
    fun setUpdateInstallArmed(armed: Boolean) = setBoolean(Key.UPDATE_INSTALL_ARMED, armed)

    /** The versionCode seen on the previous launch, used to detect that an update just landed. */
    fun getLastSeenVersionCode() = getInt(Key.LAST_SEEN_VERSION_CODE)
    fun setLastSeenVersionCode(code: Int) = setInt(Key.LAST_SEEN_VERSION_CODE, code)

    /** Version name to show a dismissable "updated successfully" banner for, or null when none. */
    fun getUpdateSuccessBannerVersion() = getString(Key.UPDATE_SUCCESS_BANNER_VERSION)
    fun setUpdateSuccessBannerVersion(version: String?) = setString(Key.UPDATE_SUCCESS_BANNER_VERSION, version)

    fun isPersistentServerEnabled() = getBoolean(Key.PERSISTENT_SERVER_ENABLED, DefaultsValue.PERSISTENT_SERVER_ENABLED)

    /** Sets whether the persistent privileged recorder daemon path is enabled. */
    fun setPersistentServerEnabled(enabled: Boolean) = setBoolean(Key.PERSISTENT_SERVER_ENABLED, enabled)

    /**
     * Whether Wireless debugging should be turned OFF once the daemon's binder is connected (and only
     * re-enabled transiently to relaunch the daemon). Only meaningful when [isPersistentServerEnabled].
     * Default false = keep WD on.
     */
    fun isWdDisableWhenIdle() = getBoolean(Key.WD_DISABLE_WHEN_IDLE, DefaultsValue.WD_DISABLE_WHEN_IDLE)

    /** Sets the "turn Wireless debugging off when the daemon is connected" policy. */
    fun setWdDisableWhenIdle(enabled: Boolean) = setBoolean(Key.WD_DISABLE_WHEN_IDLE, enabled)

    // -------- Storage & General --------

    /** Gets the user-selected folder URI for storing recordings. */
    fun getRecordingFolderUri(): Uri? = getString(Key.RECORDING_FOLDER_URI, DefaultsValue.RECORDING_FOLDER_URI)?.toUri()

    /** Sets the user-selected folder URI for storing recordings. */
    fun setRecordingFolderUri(uri: Uri?) = setString(Key.RECORDING_FOLDER_URI, uri?.toString())

    /** Gets the storage routing target (Local / Drive / Both). */
    fun getStorageTarget(): StorageTarget = StorageTarget.fromKey(getString(Key.STORAGE_TARGET, DefaultsValue.STORAGE_TARGET) ?: DefaultsValue.STORAGE_TARGET)

    /** Sets the storage routing target. */
    fun setStorageTarget(target: StorageTarget) = setString(Key.STORAGE_TARGET, target.key)

    /** Gets the user-selected Google Drive SAF folder URI for routing copies. */
    fun getDriveFolderUri(): Uri? = getString(Key.DRIVE_FOLDER_URI, DefaultsValue.DRIVE_FOLDER_URI)?.toUri()

    /** Sets the user-selected Google Drive SAF folder URI for routing copies. */
    fun setDriveFolderUri(uri: Uri?) = setString(Key.DRIVE_FOLDER_URI, uri?.toString())

    // -------- Sync Schedule --------

    /** Gets the cloud sync cadence (Immediate / Daily / Weekly). */
    fun getSyncScheduleMode(): SyncScheduleMode = SyncScheduleMode.fromKey(getString(Key.SYNC_SCHEDULE_MODE, DefaultsValue.SYNC_SCHEDULE_MODE))

    /** Sets the cloud sync cadence. */
    fun setSyncScheduleMode(mode: SyncScheduleMode) = setString(Key.SYNC_SCHEDULE_MODE, mode.key)

    /** Gets the scheduled sweep hour (0-23) for Daily/Weekly modes. */
    fun getSyncTimeHour() = getInt(Key.SYNC_TIME_HOUR, DefaultsValue.SYNC_TIME_HOUR)

    /** Sets the scheduled sweep hour (0-23). */
    fun setSyncTimeHour(hour: Int) = setInt(Key.SYNC_TIME_HOUR, hour)

    /** Gets the scheduled sweep minute (0-59) for Daily/Weekly modes. */
    fun getSyncTimeMinute() = getInt(Key.SYNC_TIME_MINUTE, DefaultsValue.SYNC_TIME_MINUTE)

    /** Sets the scheduled sweep minute (0-59). */
    fun setSyncTimeMinute(minute: Int) = setInt(Key.SYNC_TIME_MINUTE, minute)

    /** Gets the scheduled sweep day-of-week for Weekly mode (java.util.Calendar: SUNDAY=1..SATURDAY=7). */
    fun getSyncDayOfWeek() = getInt(Key.SYNC_DAY_OF_WEEK, DefaultsValue.SYNC_DAY_OF_WEEK)

    /** Sets the scheduled sweep day-of-week (java.util.Calendar: SUNDAY=1..SATURDAY=7). */
    fun setSyncDayOfWeek(day: Int) = setInt(Key.SYNC_DAY_OF_WEEK, day)

    // --- Retention (auto-delete old recordings; 0 = keep forever) ---

    /** Whether device & Drive share one retention period (true) or each has its own (false). */
    fun isRetentionLinked() = getBoolean(Key.RETENTION_LINKED, DefaultsValue.RETENTION_LINKED)

    /** Sets whether device & Drive share one retention period. */
    fun setRetentionLinked(linked: Boolean) = setBoolean(Key.RETENTION_LINKED, linked)

    /** Retention in days for on-device recordings (0 = keep forever). */
    fun getRetentionLocalDays() = getInt(Key.RETENTION_LOCAL_DAYS, DefaultsValue.RETENTION_LOCAL_DAYS)

    /** Sets the on-device retention in days (0 = keep forever). */
    fun setRetentionLocalDays(days: Int) = setInt(Key.RETENTION_LOCAL_DAYS, days)

    /** Retention in days for Drive recordings (0 = keep forever). */
    fun getRetentionDriveDays() = getInt(Key.RETENTION_DRIVE_DAYS, DefaultsValue.RETENTION_DRIVE_DAYS)

    /** Sets the Drive retention in days (0 = keep forever). */
    fun setRetentionDriveDays(days: Int) = setInt(Key.RETENTION_DRIVE_DAYS, days)

    /** Hour (0-23, local time) the daily retention sweep runs. */
    fun getRetentionTimeHour() = getInt(Key.RETENTION_TIME_HOUR, DefaultsValue.RETENTION_TIME_HOUR)

    /** Sets the retention sweep hour (0-23, local time). */
    fun setRetentionTimeHour(hour: Int) = setInt(Key.RETENTION_TIME_HOUR, hour)

    /** Minute (0-59) the daily retention sweep runs. */
    fun getRetentionTimeMinute() = getInt(Key.RETENTION_TIME_MINUTE, DefaultsValue.RETENTION_TIME_MINUTE)

    /** Sets the retention sweep minute (0-59). */
    fun setRetentionTimeMinute(minute: Int) = setInt(Key.RETENTION_TIME_MINUTE, minute)

    /** Checks if vibration is enabled for notifications/actions. */
    fun isVibrationEnabled() = getBoolean(Key.VIBRATION_ENABLED, DefaultsValue.VIBRATION_ENABLED)
    
    /** Sets whether vibration is enabled. */
    fun setVibrationEnabled(enabled: Boolean) = setBoolean(Key.VIBRATION_ENABLED, enabled)

    // -------- Automation --------

    /** Checks if auto-recording for incoming calls is enabled. */
    fun isAutoRecordIncomingEnabled() = getBoolean(Key.AUTO_RECORD_INCOMING, DefaultsValue.AUTO_RECORD_INCOMING)
    
    /** Sets whether auto-recording for incoming calls is enabled. */
    fun setAutoRecordIncomingEnabled(enabled: Boolean) = setBoolean(Key.AUTO_RECORD_INCOMING, enabled)

    /** Checks if auto-recording for outgoing calls is enabled. */
    fun isAutoRecordOutgoingEnabled() = getBoolean(Key.AUTO_RECORD_OUTGOING, DefaultsValue.AUTO_RECORD_OUTGOING)
    
    /** Sets whether auto-recording for outgoing calls is enabled. */
    fun setAutoRecordOutgoingEnabled(enabled: Boolean) = setBoolean(Key.AUTO_RECORD_OUTGOING, enabled)

    // -------- Filters & Contacts --------

    /** Checks if recording should be ignored for incoming anonymous calls. */
    fun isIgnoreAnonymousIncomingEnabled() = getBoolean(Key.IGNORE_ANONYMOUS_INCOMING, DefaultsValue.IGNORE_ANONYMOUS_INCOMING)
    
    /** Sets whether to ignore recording for incoming anonymous calls. */
    fun setIgnoreAnonymousIncomingEnabled(enabled: Boolean) = setBoolean(Key.IGNORE_ANONYMOUS_INCOMING, enabled)

    /** Checks if recording should be ignored for incoming cross-country calls. */
    fun isIgnoreCrossCountryIncomingEnabled() = getBoolean(Key.IGNORE_CROSS_COUNTRY_INCOMING, DefaultsValue.IGNORE_CROSS_COUNTRY_INCOMING)
    
    /** Sets whether to ignore recording for incoming cross-country calls. */
    fun setIgnoreCrossCountryIncomingEnabled(enabled: Boolean) = setBoolean(Key.IGNORE_CROSS_COUNTRY_INCOMING, enabled)

    /** Checks if recording should be ignored for outgoing cross-country calls. */
    fun isIgnoreCrossCountryOutgoingEnabled() = getBoolean(Key.IGNORE_CROSS_COUNTRY_OUTGOING, DefaultsValue.IGNORE_CROSS_COUNTRY_OUTGOING)
    
    /** Sets whether to ignore recording for outgoing cross-country calls. */
    fun setIgnoreCrossCountryOutgoingEnabled(enabled: Boolean) = setBoolean(Key.IGNORE_CROSS_COUNTRY_OUTGOING, enabled)

    /** Gets the contacts mode defining which incoming calls are ignored. */
    fun getIgnoreContactsModeIncoming() = IgnoreContactsMode.fromKey(getString(Key.IGNORE_CONTACTS_MODE_INCOMING, DefaultsValue.IGNORE_CONTACTS_MODE_INCOMING.key))
    
    /** Sets the contacts mode defining which incoming calls are ignored. */
    fun setIgnoreContactsModeIncoming(mode: IgnoreContactsMode) = setString(Key.IGNORE_CONTACTS_MODE_INCOMING, mode.key)

    /** Gets the contacts mode defining which outgoing calls are ignored. */
    fun getIgnoreContactsModeOutgoing() = IgnoreContactsMode.fromKey(getString(Key.IGNORE_CONTACTS_MODE_OUTGOING, DefaultsValue.IGNORE_CONTACTS_MODE_OUTGOING.key))
    
    /** Sets the contacts mode defining which outgoing calls are ignored. */
    fun setIgnoreContactsModeOutgoing(mode: IgnoreContactsMode) = setString(Key.IGNORE_CONTACTS_MODE_OUTGOING, mode.key)

    /** Gets the set of specific contact numbers to ignore for incoming calls. */
    fun getIgnoredContactsIncoming() = getStringSet(Key.IGNORED_CONTACTS_INCOMING, DefaultsValue.IGNORED_CONTACTS_INCOMING)
    
    /** Sets the set of specific contact numbers to ignore for incoming calls. */
    fun setIgnoredContactsIncoming(numbers: Set<String>) = setStringSet(Key.IGNORED_CONTACTS_INCOMING, numbers)

    /** Gets the set of specific contact numbers to ignore for outgoing calls. */
    fun getIgnoredContactsOutgoing() = getStringSet(Key.IGNORED_CONTACTS_OUTGOING, DefaultsValue.IGNORED_CONTACTS_OUTGOING)
    
    /** Sets the set of specific contact numbers to ignore for outgoing calls. */
    fun setIgnoredContactsOutgoing(numbers: Set<String>) = setStringSet(Key.IGNORED_CONTACTS_OUTGOING, numbers)

    // -------- Debug --------

    /** Checks if logging features are enabled. */
    fun isLoggingEnabled() = getBoolean(Key.LOGGING_ENABLED, DefaultsValue.LOGGING_ENABLED)

    /** Sets whether logging features are enabled. */
    fun setLoggingEnabled(enabled: Boolean) = setBoolean(Key.LOGGING_ENABLED, enabled)

    /** Checks if debug features are enabled. */
    fun isDebugEnabled() = getBoolean(Key.DEBUG_ENABLED, DefaultsValue.DEBUG_ENABLED)
    
    /** Sets whether debug features are enabled. */
    fun setDebugEnabled(enabled: Boolean) = setBoolean(Key.DEBUG_ENABLED, enabled)

    /** Gets the caller number override used for debugging. */
    fun getDebugCallerNumber() = getString(Key.DEBUG_CALLER_NUMBER, DefaultsValue.DEBUG_CALLER_NUMBER) ?: DefaultsValue.DEBUG_CALLER_NUMBER

    /** Sets the caller number override used for debugging. */
    fun setDebugCallerNumber(number: String) = setString(Key.DEBUG_CALLER_NUMBER, number)

    /** Whether developer options (the Debug section) have been unlocked via the hidden gesture. */
    fun isDeveloperModeUnlocked() = getBoolean(Key.DEVELOPER_MODE_UNLOCKED, DefaultsValue.DEVELOPER_MODE_UNLOCKED)

    /** Sets whether developer options are unlocked. */
    fun setDeveloperModeUnlocked(enabled: Boolean) = setBoolean(Key.DEVELOPER_MODE_UNLOCKED, enabled)

    // -------- Audio/Scrcpy Quality --------

    /** Gets the configured audio source for scrcpy integration. */
    fun getAudioSource() = getString(Key.AUDIO_SOURCE, DefaultsValue.AUDIO_SOURCE) ?: DefaultsValue.AUDIO_SOURCE
    
    /** Sets the configured audio source. */
    fun setAudioSource(source: String) = setString(Key.AUDIO_SOURCE, source)

    /** Gets the configured audio codec for scrcpy integration. */
    fun getAudioCodec() = getString(Key.AUDIO_CODEC, DefaultsValue.AUDIO_CODEC) ?: DefaultsValue.AUDIO_CODEC
    
    /** Sets the configured audio codec. */
    fun setAudioCodec(codec: String) = setString(Key.AUDIO_CODEC, codec)

    /** Gets the configured audio bitrate. */
    fun getAudioBitRate() = getInt(Key.AUDIO_BITRATE, DefaultsValue.AUDIO_BITRATE)

    /** Sets the configured audio bitrate. */
    fun setAudioBitRate(bitRate: Int) = setInt(Key.AUDIO_BITRATE, bitRate)

    // -------- File Naming --------

    /** Gets the user configured file name template. */
    fun getFileNameTemplate() = getString(Key.FILE_NAME_TEMPLATE, DefaultsValue.FILE_NAME_TEMPLATE) ?: DefaultsValue.FILE_NAME_TEMPLATE

    /** Sets the user configured file name template. */
    fun setFileNameTemplate(template: String) = setString(Key.FILE_NAME_TEMPLATE, template)

    // -------- UI & Appearance --------

    /** Gets the current UI theme mode. */
    fun getThemeMode() = ThemeMode.fromKey(getString(Key.THEME_MODE, DefaultsValue.THEME_MODE.key))
    
    /** Sets the current UI theme mode. */
    fun setThemeMode(mode: ThemeMode) = setString(Key.THEME_MODE, mode.key)

    /** Checks if dynamic color (Material You) is enabled. */
    fun isDynamicColorEnabled() = getBoolean(Key.DYNAMIC_COLOR, DefaultsValue.DYNAMIC_COLOR)
    
    /** Sets whether dynamic color is enabled. */
    fun setDynamicColorEnabled(enabled: Boolean) = setBoolean(Key.DYNAMIC_COLOR, enabled)

    /** Checks if toast notifications are enabled. */
    fun isShowToastsEnabled() = getBoolean(Key.SHOW_TOASTS, DefaultsValue.SHOW_TOASTS)

    /** Sets whether toast notifications are enabled. */
    fun setShowToastsEnabled(enabled: Boolean) = setBoolean(Key.SHOW_TOASTS, enabled)

}
