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
import com.baba.callvault.transcription.TranscriptionEstimate
import com.baba.callvault.transcription.TranscriptionLanguageChoice
import com.baba.callvault.transcription.model.TranscriptionModel
import com.baba.callvault.ui.navigation.HomeSection

/**
 * AppPreferences wraps [android.content.SharedPreferences] to provide typed access to all
 * user-configurable settings stored on the device.
 */
class AppPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "callvault_prefs"

        /** Public key id for the available-update tag, for change-listener comparisons. */
        const val AVAILABLE_UPDATE_TAG_KEY = "available_update_tag"

        /** Public key id for the privileged mode, so the UI can react the moment it changes. */
        const val PRIVILEGED_MODE_KEY = "privileged_mode"

        /**
         * Range for the randomly-chosen loopback ADB port ([getLoopbackAdbPort]). Deliberately an
         * uncommon high range: above the ephemeral/registered clutter, and NOT the well-known adb
         * 5555, the adb-server 5037, or the emulator 5554–5585 band.
         */
        private const val LOOPBACK_PORT_MIN = 47000
        private const val LOOPBACK_PORT_MAX = 59999

        /**
         * Marker for the configuration the stored transcription speeds describe
         * ([getRtfCalibrationThreads]).
         *
         * Versioned, because the thread count is not the only part of "this machine" that can move:
         * so can the arithmetic. `_v2` marks ggml being built with per-CPU kernel variants
         * (dotprod/i8mm) instead of the plain ARMv8 baseline, which made transcription about 1.2x
         * faster on the phones that have them. Bump the suffix on any change of that kind. Leaving
         * it alone would keep every factor measured under the old kernels and quote a pessimistic
         * estimate on every phone that had ever transcribed anything.
         *
         * An old marker needs no migration: it starts with the same prefix as the speeds, so the
         * sweep in [setRtfCalibrationThreads] removes it along with them.
         */
        private const val RTF_CALIBRATION_KEY = "transcription_rtf_threads_v2"

        /**
         * Deliberately NOT the old "transcription_rtf_" prefix.
         *
         * What is stored under it changed meaning: it is now work time over *billable* audio, with
         * the model load charged separately, where it used to be whole-run time over raw audio
         * length. Reading an old value as a new one would be wrong — and on the phones that hit
         * issue #26 the old value is an absurd number that would take a dozen runs to average away.
         * A new prefix retires those quietly, and the first run after updating measures afresh.
         */
        private const val RATE_PREFIX = "transcription_rate_v2_"
        private const val LOAD_PREFIX = "transcription_load_v2_"
        private const val LEGACY_RATE_PREFIX = "transcription_rtf_"
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
        // True keeps CallVault's behaviour for carrier calls exactly as it has always been: record
        // when the rules below say so, otherwise offer a Record button. False is the opt-in
        // "app calls only" mode — no recording AND no standby notification for phone calls.
        const val CARRIER_RECORDING_ENABLED = true
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

        // --- Transcription (on-device speech to text) ---
        // MANUAL (default) transcribes only what the user taps. AUTOMATIC sweeps everything not yet
        // transcribed at TRANSCRIPTION_HOUR:TRANSCRIPTION_MINUTE. Manual is the default because a
        // transcription costs roughly the call's own duration in CPU, which is not something to start
        // doing to someone's phone unasked. Charging is required by default for the same reason.
        val TRANSCRIPTION_MODE = TranscriptionMode.MANUAL.key
        const val TRANSCRIPTION_HOUR = 2       // 0-23, device local time
        const val TRANSCRIPTION_MINUTE = 0     // 0-59
        const val TRANSCRIPTION_REQUIRES_CHARGING = true
        // How many recordings one automatic run takes on. 0 means no limit. Configurable because the
        // right answer depends entirely on call length: 25 short calls is a quick sweep, 25 long ones
        // is most of a night.
        const val TRANSCRIPTION_BATCH_LIMIT = 25
        const val TRANSCRIPTION_CONFIRM_BEFORE_RUN = true
        // Whether the summariser's requirements are shown before the download. On by default: it is
        // 3.5 GB and about the same again in memory, and someone should be told before they start.
        const val SUMMARY_CONFIRM_REQUIREMENTS = true
        val TRANSCRIPTION_MODEL_ID = TranscriptionModel.DEFAULT.id
        // The phone's own language, and auto-detect (null) only when the app does not offer it.
        //
        // Not a constant, because the right default is the one this phone is set to. It used to be a
        // flat null, which meant every install transcribed on auto-detect until someone went looking
        // for the setting — and auto-detect does not fail loudly, it returns Hebrew spelled out in
        // Latin letters, with the whole call as one segment. Naming the language outright is more
        // reliable, so the app names it rather than leaving it blank.
        //
        // Null still means genuine detection where it survives: our JNI sets whisper's
        // detect_language whenever no language is given, rather than falling back to English the way
        // upstream's JNI does.
        val TRANSCRIPTION_LANGUAGE: String? get() = TranscriptionLanguageChoice.defaultLanguage()

        // Standalone: one app, no dependency on anything else being installed — the project's whole
        // premise. An install that predates Shizuku support has no stored value and must land here.
        val PRIVILEGED_MODE: String = PrivilegedMode.STANDALONE.key

        // Off: one language for every call is the right assumption for most phones, and an extra
        // dialog between a tap and its result is a cost worth paying only by people who take calls in
        // more than one language.
        const val TRANSCRIPTION_ASK_LANGUAGE = false

        // --- Retention (auto-delete old recordings) ---
        // Delete recordings older than N days. 0 = keep forever (OFF). Applied per copy: device copies
        // use RETENTION_LOCAL_DAYS, Drive copies use RETENTION_DRIVE_DAYS. When RETENTION_LINKED is true
        // the UI shows one selector that writes both to the same value. Defaults to OFF so nothing is
        // ever deleted until the user explicitly opts in.
        const val RETENTION_LINKED = true
        // Off. A default that discarded would silently start deleting recordings for everyone who
        // upgrades, and the calls it would take are exactly the ones nobody would notice going.
        // Off. It puts a second file in the user's folder for every call — a visible change to
        // something they look at — and it only earns its keep for someone using a tool that reads it.
        const val WRITE_METADATA_FILE = false

        const val MIN_DURATION_SECONDS = 0

        // No cap. Like the retention period beside it, a shipped default that deleted would take
        // recordings from everyone who upgrades without ever having asked for it.
        const val STORAGE_CAP_BYTES = 0L

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
        // Check for new GitHub releases (a tiny daily JSON query). ON by default; a found update
        // surfaces as a Home banner + notification and installs only on an explicit user tap.
        const val UPDATE_CHECK_ENABLED = true

        // --- Persistent recorder server (CallVault Plan 5) ---
        // OFF by default: when false the existing local recording path runs unchanged. When true the
        // recording layer drives the detached privileged daemon (RecorderServer) over binder instead.
        const val PERSISTENT_SERVER_ENABLED = false

        // WD policy for persistent-server mode. false (default) = keep Wireless debugging ON (safer,
        // matches the legacy behaviour). true = turn WD OFF once the daemon's binder is connected and
        // re-enable it only transiently to (re)launch the daemon — the persistent-server payoff.
        const val WD_DISABLE_WHEN_IDLE = false

        // --- Resilient recording (audio-capture handoff, Option B) ---
        // OFF by default: the recording path is byte-identical to the daemon-mode default. When true and
        // the source is handoff-compatible, the daemon hands its live AudioRecord to the app, which reads
        // the ring + encodes — so a recording SURVIVES the daemon being killed mid-call.
        const val HANDOFF_PERSIST_ENABLED = false
        const val VOIP_RECORDING_ENABLED = false
        // True = start an app call's recording by itself, which is what VoIP recording has always
        // done. False = detect the call and offer a Record button instead. Only consulted when
        // VOIP_RECORDING_ENABLED is on.
        const val VOIP_AUTO_START = true

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

        // What the last real `pm grant` from the shell showed, for phones whose OEM refuses it. Remembered
        // because most OEMs state it nowhere readable — see [ShellGrantGate].
        SHELL_GRANT_STATE("shell_grant_state"),

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

        // --- Transcription ---
        TRANSCRIPTION_MODE("transcription_mode"),
        TRANSCRIPTION_HOUR("transcription_hour"),
        TRANSCRIPTION_MINUTE("transcription_minute"),
        TRANSCRIPTION_REQUIRES_CHARGING("transcription_requires_charging"),
        TRANSCRIPTION_BATCH_LIMIT("transcription_batch_limit"),
        TRANSCRIPTION_CONFIRM_BEFORE_RUN("transcription_confirm_before_run"),
        SUMMARY_CONFIRM_REQUIREMENTS("summary_confirm_requirements"),
        SUMMARY_LANGUAGE("summary_language"),
        TRANSCRIPTION_MODEL_ID("transcription_model_id"),
        TRANSCRIPTION_LANGUAGE("transcription_language"),
        TRANSCRIPTION_ASK_LANGUAGE("transcription_ask_language"),
        PRIVILEGED_MODE("privileged_mode"),
        /** Which switches *CallVault* turned off for the current mode, so a round trip can undo it. */
        MODE_AUTO_DISABLED("mode_auto_disabled"),
        SPEAKER_MAP_OVERRIDE("speaker_map_override"),
        SPEAKER_MAP_CONFIRMED("speaker_map_confirmed"),
        APP_LOCK_ENABLED("app_lock_enabled"),

        // --- Retention ---
        RETENTION_LINKED("retention_linked"),
        WRITE_METADATA_FILE("write_metadata_file"),
        VOIP_EXCLUDED_PACKAGES("voip_excluded_packages"),
        MIN_DURATION_SECONDS("min_duration_seconds"),
        STORAGE_CAP_BYTES("storage_cap_bytes"),
        RETENTION_LOCAL_DAYS("retention_local_days"),
        RETENTION_DRIVE_DAYS("retention_drive_days"),
        RETENTION_TIME_HOUR("retention_time_hour"),
        RETENTION_TIME_MINUTE("retention_time_minute"),

        // --- ADB ---
        ADB_PAIRED("adb_paired"),
        LOOPBACK_ADB_PORT("loopback_adb_port"),
        OFFLINE_RECORDING_ENABLED("offline_recording_enabled"),
        KEEP_ORIGINALS_AFTER_MERGE("keep_originals_after_merge"),

        // --- In-app updates ---
        UPDATE_CHECK_ENABLED("update_check_enabled"),
        AVAILABLE_UPDATE_TAG("available_update_tag"),
        PENDING_UPDATE_TAG("pending_update_tag"),
        LAST_NOTIFIED_UPDATE_TAG("last_notified_update_tag"),
        LAST_UPDATE_CHECK_MILLIS("last_update_check_millis"),
        UPDATE_INSTALL_ARMED("update_install_armed"),
        LAST_SEEN_VERSION_CODE("last_seen_version_code"),
        UPDATE_SUCCESS_BANNER_VERSION("update_success_banner_version"),
        WHATS_NEW_SEEN_VERSION("whats_new_seen_version"),
        USB_DEFAULT_MODE("usb_default_mode"),
        UPDATE_SOURCE_OVERRIDE_URL("update_source_override_url"),

        // --- Persistent recorder server (CallVault Plan 5) ---
        PERSISTENT_SERVER_ENABLED("persistent_server_enabled"),
        WD_DISABLE_WHEN_IDLE("wd_disable_when_idle"),
        HANDOFF_PERSIST_ENABLED("handoff_persist_enabled"),
        VOIP_RECORDING_ENABLED("voip_recording_enabled"),
        VOIP_AUTO_START("voip_auto_start"),
        
        // --- Automation ---
        CARRIER_RECORDING_ENABLED("carrier_recording_enabled"),
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
        WD_ENABLED_BY_US("wd_enabled_by_us"),
        WD_TURNED_OFF_BY_USER("wd_turned_off_by_user"),
        WD_ENFORCED("wd_enforced"),
        LOG_PSEUDONYM_SALT("log_pseudonym_salt"),
        LOGCAT_RING_PREVIOUS_KIB("logcat_ring_previous_kib"),
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
        SHOW_TOASTS("show_toasts"),

        // The section of Home the user was last in. Absent until they have been somewhere, which is
        // a state with its own meaning — see HomeSection.opening — so there is no DefaultsValue entry.
        LAST_HOME_SECTION("last_home_section");
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

    /** What the last self-grant attempt over the ADB shell showed ([ShellGrantGate.ShellGrantState] name), or null. */
    fun getShellGrantState(): String? = getString(Key.SHELL_GRANT_STATE)

    /** Records what a self-grant attempt showed, so a screen can explain a refusal that happened minutes ago. */
    fun setShellGrantState(state: String) = setString(Key.SHELL_GRANT_STATE, state)

    /** Whether the one-time post-onboarding setup wizard has been completed. */
    fun isWizardCompleted() = getBoolean(Key.WIZARD_COMPLETED, DefaultsValue.WIZARD_COMPLETED)

    /** Marks the one-time setup wizard as completed (set on the wizard's Finish step). */
    fun setWizardCompleted(enabled: Boolean) = setBoolean(Key.WIZARD_COMPLETED, enabled)

    /** Whether the one-time ADB wireless-debugging pairing has been completed. */
    fun isAdbPaired() = getBoolean(Key.ADB_PAIRED, DefaultsValue.ADB_PAIRED)

    /** Marks the one-time ADB pairing as completed (set after the first successful connection). */
    fun setAdbPaired(paired: Boolean) = setBoolean(Key.ADB_PAIRED, paired)

    /**
     * Returns this install's fixed loopback ADB port for the classic `adb tcpip` listener
     * (`127.0.0.1:<port>`), which — unlike Wireless Debugging — survives WiFi-off because loopback
     * is always up. Generated ONCE at random in an uncommon range (deliberately NOT the well-known
     * 5555) on first read and persisted, so it is stable across app updates (SharedPreferences
     * survives in-place same-cert updates) and across reboots (we re-arm adbd with the same number).
     * It resets only on uninstall/clear-data, which also wipes the ADB pairing and forces re-onboard.
     */
    fun getLoopbackAdbPort(): Int {
        val existing = getInt(Key.LOOPBACK_ADB_PORT, 0)
        if (existing in LOOPBACK_PORT_MIN..LOOPBACK_PORT_MAX) return existing
        val port = LOOPBACK_PORT_MIN + java.security.SecureRandom().nextInt(LOOPBACK_PORT_MAX - LOOPBACK_PORT_MIN + 1)
        setInt(Key.LOOPBACK_ADB_PORT, port)
        return port
    }

    /**
     * Whether the user has OPTED IN to "offline recording (no Wi-Fi)" — the loopback `adb tcpip` mode.
     * OFF by default: it opens a local debugging port bound to all interfaces (RSA-key-gated but a real
     * surface), so it's only enabled after an explicit security-warning confirmation. When on,
     * [com.baba.callvault.integrations.adb.AdbShell.ensureConnected] prefers the loopback port so calls
     * record with no Wi-Fi.
     */
    fun isOfflineRecordingEnabled() = getBoolean(Key.OFFLINE_RECORDING_ENABLED, false)

    /** Sets the offline-recording (loopback) opt-in flag. */
    fun setOfflineRecordingEnabled(enabled: Boolean) = setBoolean(Key.OFFLINE_RECORDING_ENABLED, enabled)

    /**
     * Whether merging keeps the calls it was made from.
     *
     * Off by default, so a merge leaves one recording — the point of the feature. Keeping them is
     * safe to offer because it is not what makes un-merge possible: the merged file contains the
     * originals' encoded frames, so un-merge cuts them back out exactly whether or not the separate
     * files are still there. This setting is for people who would rather hold the originals anyway,
     * and it costs roughly double the storage on a merged call.
     */
    fun isKeepOriginalsAfterMerge() = getBoolean(Key.KEEP_ORIGINALS_AFTER_MERGE, false)
    fun setKeepOriginalsAfterMerge(keep: Boolean) = setBoolean(Key.KEEP_ORIGINALS_AFTER_MERGE, keep)

    // -------- Persistent Recorder Server (CallVault Plan 5) --------

    /**
     * Whether the persistent privileged recorder daemon path is enabled. OFF by default: when false the
     * existing local recording pipeline runs unchanged; when true the recording layer drives the
     * detached daemon ([com.baba.callvault.server.RecorderServer]) over binder.
     */
    fun isUpdateCheckEnabled() = getBoolean(Key.UPDATE_CHECK_ENABLED, DefaultsValue.UPDATE_CHECK_ENABLED)
    fun setUpdateCheckEnabled(enabled: Boolean) = setBoolean(Key.UPDATE_CHECK_ENABLED, enabled)

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

    /**
     * The app version the post-update release note was last shown for, or null if never.
     *
     * A version rather than a per-feature boolean: the note now lists the last few releases together,
     * so it should return once for each new version and then stay away. The previous design needed a
     * brand-new preference for every feature introduced, which does not scale.
     */
    fun getWhatsNewSeenVersion() = getString(Key.WHATS_NEW_SEEN_VERSION)
    fun setWhatsNewSeenVersion(version: String?) = setString(Key.WHATS_NEW_SEEN_VERSION, version)

    /** Cached name of the last-read [com.baba.callvault.integrations.adb.UsbDefaultMode], or null. */
    fun getUsbDefaultMode() = getString(Key.USB_DEFAULT_MODE)
    fun setUsbDefaultMode(mode: String?) = setString(Key.USB_DEFAULT_MODE, mode)

    /**
     * TEST-ONLY: a GitHub release API URL to fetch the update from instead of `/latest`. Not exposed
     * in the UI — set via adb to verify the update flow against a pre-release before publishing. When
     * set, prerelease/draft releases are accepted (so a `-rc` tag can be the target). Null in normal use.
     */
    fun getUpdateSourceOverrideUrl() = getString(Key.UPDATE_SOURCE_OVERRIDE_URL)
    fun setUpdateSourceOverrideUrl(url: String?) = setString(Key.UPDATE_SOURCE_OVERRIDE_URL, url)

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

    /**
     * Whether to record VoIP calls (WhatsApp/Signal/Telegram/…) as well as carrier calls. EXPERIMENTAL
     * and off by default: consent law for VoIP is stricter in many jurisdictions, and an app that opts
     * out of audio capture cannot be recorded at all.
     */
    fun isVoipRecordingEnabled() = getBoolean(Key.VOIP_RECORDING_ENABLED, DefaultsValue.VOIP_RECORDING_ENABLED)
    fun setVoipRecordingEnabled(enabled: Boolean) = setBoolean(Key.VOIP_RECORDING_ENABLED, enabled)

    /**
     * Whether a detected app call starts recording by itself (true, the long-standing behaviour) or
     * only offers a Record button (false — "Ask me").
     *
     * Meaningless unless [isVoipRecordingEnabled] is on: with VoIP recording off nothing watches for
     * app calls at all, so there is nothing to ask about.
     */
    fun isVoipAutoStartEnabled() = getBoolean(Key.VOIP_AUTO_START, DefaultsValue.VOIP_AUTO_START)
    fun setVoipAutoStartEnabled(enabled: Boolean) = setBoolean(Key.VOIP_AUTO_START, enabled)

    /**
     * Whether "Resilient recording" (the audio-capture handoff, Option B) is enabled. Default false =
     * the recording path is byte-identical to daemon mode. When true and the source is handoff-compatible,
     * the app holds the live capture and a recording survives the daemon dying mid-call.
     */
    fun isHandoffPersistEnabled() = getBoolean(Key.HANDOFF_PERSIST_ENABLED, DefaultsValue.HANDOFF_PERSIST_ENABLED)

    /** Sets whether the resilient-recording (audio-handoff) path is enabled. */
    fun setHandoffPersistEnabled(enabled: Boolean) = setBoolean(Key.HANDOFF_PERSIST_ENABLED, enabled)

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

    // -------- Transcription --------

    /** Gets when calls are transcribed (Manual / Automatic). */
    fun getTranscriptionMode(): TranscriptionMode =
        TranscriptionMode.fromKey(getString(Key.TRANSCRIPTION_MODE, DefaultsValue.TRANSCRIPTION_MODE))

    /** Sets when calls are transcribed. */
    fun setTranscriptionMode(mode: TranscriptionMode) = setString(Key.TRANSCRIPTION_MODE, mode.key)

    /** Gets the hour (0-23, device local time) of the automatic transcription run. */
    fun getTranscriptionHour() = getInt(Key.TRANSCRIPTION_HOUR, DefaultsValue.TRANSCRIPTION_HOUR)

    /** Sets the hour (0-23) of the automatic transcription run. */
    fun setTranscriptionHour(hour: Int) = setInt(Key.TRANSCRIPTION_HOUR, hour)

    /** Gets the minute (0-59) of the automatic transcription run. */
    fun getTranscriptionMinute() = getInt(Key.TRANSCRIPTION_MINUTE, DefaultsValue.TRANSCRIPTION_MINUTE)

    /** Sets the minute (0-59) of the automatic transcription run. */
    fun setTranscriptionMinute(minute: Int) = setInt(Key.TRANSCRIPTION_MINUTE, minute)

    /** Whether the automatic run waits for the phone to be charging. On by default. */
    fun getTranscriptionRequiresCharging() =
        getBoolean(Key.TRANSCRIPTION_REQUIRES_CHARGING, DefaultsValue.TRANSCRIPTION_REQUIRES_CHARGING)

    /** Sets whether the automatic run waits for the phone to be charging. */
    fun setTranscriptionRequiresCharging(required: Boolean) =
        setBoolean(Key.TRANSCRIPTION_REQUIRES_CHARGING, required)

    /** How many recordings one automatic run takes on; 0 means no limit. */
    fun getTranscriptionBatchLimit() =
        getInt(Key.TRANSCRIPTION_BATCH_LIMIT, DefaultsValue.TRANSCRIPTION_BATCH_LIMIT)

    /** Sets how many recordings one automatic run takes on; 0 means no limit. */
    fun setTranscriptionBatchLimit(limit: Int) = setInt(Key.TRANSCRIPTION_BATCH_LIMIT, limit)

    /** Whether tapping Transcribe asks first, showing how long it will take. On by default. */
    fun getTranscriptionConfirmBeforeRun() =
        getBoolean(Key.TRANSCRIPTION_CONFIRM_BEFORE_RUN, DefaultsValue.TRANSCRIPTION_CONFIRM_BEFORE_RUN)

    /** Sets whether tapping Transcribe asks first. Reachable from Settings, so it can be undone. */
    fun setTranscriptionConfirmBeforeRun(confirm: Boolean) =
        setBoolean(Key.TRANSCRIPTION_CONFIRM_BEFORE_RUN, confirm)

    /**
     * Whether the summariser's requirements are shown before the download starts. On by default.
     *
     * The dialog can turn itself off, so this has to be reachable from Settings as well — a dialog
     * that can permanently remove itself with no way back is a trap, which is the same reason
     * [getTranscriptionConfirmBeforeRun] lives beside its switch.
     */
    fun getSummaryConfirmRequirements() =
        getBoolean(Key.SUMMARY_CONFIRM_REQUIREMENTS, DefaultsValue.SUMMARY_CONFIRM_REQUIREMENTS)

    fun setSummaryConfirmRequirements(confirm: Boolean) =
        setBoolean(Key.SUMMARY_CONFIRM_REQUIREMENTS, confirm)

    /**
     * The language summaries are written in, or null to work it out per recording.
     *
     * Separate from the transcription language on purpose. Whisper's setting says what language to
     * *listen* for; this says what language to *write*, and a user who transcribes with auto-detect
     * still wants their summaries in one predictable language. Null does not mean "let the model
     * decide" — see [com.baba.callvault.summary.SummaryLanguage], which always resolves it to a
     * concrete language before the prompt is built.
     */
    fun getSummaryLanguage(): String? = getString(Key.SUMMARY_LANGUAGE, null)

    fun setSummaryLanguage(language: String?) = setString(Key.SUMMARY_LANGUAGE, language)

    /**
     * This phone's measured real-time factor for [modelId], or null before it has ever run.
     *
     * Keyed by model because they differ by roughly 3x, and stored per device because the published
     * figures were measured on one phone. Held as a string: SharedPreferences has no double, and a
     * float would round a figure used to multiply hour-long recordings.
     */
    fun getTranscriptionRtf(modelId: String): Double? =
        prefs.getString(RATE_PREFIX + modelId, null)?.toDoubleOrNull()

    /** Records this phone's measured real-time factor for [modelId]. */
    fun setTranscriptionRtf(modelId: String, rtf: Double) =
        prefs.edit { putString(RATE_PREFIX + modelId, rtf.toString()) }

    /**
     * This phone's measured fixed cost per run for [modelId], or null before it has ever run.
     *
     * Separate from the rate because it behaves differently: loading an 874 MB model costs the same
     * whether the call is ten seconds or ten minutes. Folding it into the rate is what made short
     * clips measure as an impossibly slow phone in issue #26.
     */
    fun getTranscriptionLoadMs(modelId: String): Long? =
        prefs.getString(LOAD_PREFIX + modelId, null)?.toLongOrNull()

    /** Records this phone's measured fixed cost per run for [modelId]. */
    fun setTranscriptionLoadMs(modelId: String, loadMs: Long) =
        prefs.edit { putString(LOAD_PREFIX + modelId, loadMs.toString()) }

    /** Whether this phone has ever timed a real run of [modelId], as opposed to inheriting a seed. */
    fun hasMeasuredRun(modelId: String): Boolean = getTranscriptionRtf(modelId) != null

    /**
     * What a run of [model] costs on this phone: measured where it has been measured, seeded from
     * the model's published figures where it has not.
     *
     * One accessor rather than two lookups at each call site. The confirmation dialog and the
     * progress pill both need this, they must agree — a dialog promising two minutes over a bar
     * pacing itself to five is worse than either alone — and the previous shape had each of them
     * assembling it themselves from the same two preferences.
     */
    fun getRunCost(model: TranscriptionModel): TranscriptionEstimate.RunCost =
        TranscriptionEstimate.RunCost(
            loadMs = getTranscriptionLoadMs(model.id) ?: model.seedLoadMs,
            rtf = getTranscriptionRtf(model.id) ?: model.realTimeFactor,
        )

    /**
     * The thread policy the stored speeds were measured under.
     *
     * A measured factor only describes the machine that produced it, and the number of threads
     * whisper runs on is part of that machine. When the policy changes, every stored speed becomes a
     * confident description of a configuration that no longer exists — so it is discarded rather than
     * slowly averaged away over the next several runs, each of which would quote a wrong estimate.
     */
    fun getRtfCalibrationThreads(): Int = prefs.getInt(RTF_CALIBRATION_KEY, 0)

    /** Records the policy and forgets speeds measured under a different one. */
    fun setRtfCalibrationThreads(threads: Int) {
        if (getRtfCalibrationThreads() == threads) return
        prefs.edit {
            prefs.all.keys
                .filter { key ->
                    // LEGACY_RATE_PREFIX is swept too. Values under it were measured against raw
                    // audio length including the model load, which is a different quantity from what
                    // is stored now, so they must never be read back as if they meant the same thing.
                    (key.startsWith(RATE_PREFIX) || key.startsWith(LOAD_PREFIX) ||
                        key.startsWith(LEGACY_RATE_PREFIX)) && key != RTF_CALIBRATION_KEY
                }
                .forEach { remove(it) }
            putInt(RTF_CALIBRATION_KEY, threads)
        }
    }

    /** Gets the chosen model tier. */
    fun getTranscriptionModelId(): String =
        getString(Key.TRANSCRIPTION_MODEL_ID, DefaultsValue.TRANSCRIPTION_MODEL_ID)
            ?: DefaultsValue.TRANSCRIPTION_MODEL_ID

    /** Sets the chosen model tier. */
    fun setTranscriptionModelId(id: String) = setString(Key.TRANSCRIPTION_MODEL_ID, id)

    /**
     * Gets the language passed to whisper, or null to auto-detect.
     *
     * Not a soft setting: a call transcribed under the wrong language comes back as fluent nonsense
     * rather than as an error, so an unanswered question here is worse than most.
     *
     * Which is why "nobody has chosen" resolves to [DefaultsValue.TRANSCRIPTION_LANGUAGE] — the phone's
     * own language — and an explicit auto-detect is stored as [TranscriptionLanguageChoice.AUTO] rather
     * than as an absent key. Writing null for auto-detect would make the two indistinguishable and the
     * default would silently override the user, exactly as it does in work input data.
     */
    fun getTranscriptionLanguage(): String? =
        when (val stored = getString(Key.TRANSCRIPTION_LANGUAGE)) {
            null -> DefaultsValue.TRANSCRIPTION_LANGUAGE
            TranscriptionLanguageChoice.AUTO -> null
            else -> stored
        }

    /** Sets the language passed to whisper, or null to auto-detect. */
    fun setTranscriptionLanguage(language: String?) =
        setString(Key.TRANSCRIPTION_LANGUAGE, TranscriptionLanguageChoice.encode(language))

    /**
     * Whether tapping Transcribe asks which language, instead of always using the setting above.
     *
     * For phones that take calls in more than one language, where a single pin is wrong about half the
     * time. The answer applies to that recording only — see
     * [com.baba.callvault.transcription.TranscriptionLanguageChoice].
     */
    fun getTranscriptionAskLanguage() =
        getBoolean(Key.TRANSCRIPTION_ASK_LANGUAGE, DefaultsValue.TRANSCRIPTION_ASK_LANGUAGE)

    /** Sets whether tapping Transcribe asks which language. */
    fun setTranscriptionAskLanguage(ask: Boolean) = setBoolean(Key.TRANSCRIPTION_ASK_LANGUAGE, ask)

    // -------- Where privileges come from --------

    /**
     * Whether the recorder is started by CallVault's own embedded ADB or by a Shizuku server.
     *
     * Never null and never unknown: an unrecognised stored value answers
     * [PrivilegedMode.STANDALONE], so a downgrade cannot leave the app in a mode it cannot serve.
     */
    fun getPrivilegedMode(): PrivilegedMode =
        PrivilegedMode.fromKey(getString(Key.PRIVILEGED_MODE, DefaultsValue.PRIVILEGED_MODE))

    /** Sets where privileges come from. Changing this tears down one backend and starts the other. */
    fun setPrivilegedMode(mode: PrivilegedMode) = setString(Key.PRIVILEGED_MODE, mode.key)

    /**
     * Turns off every opt-in [mode] cannot honour, and reports what was turned off.
     *
     * Called when the mode changes. Leaving them on would be worse than cosmetic: each of these reads as
     * a promise on the settings screen, and two of them (resilient recording, VoIP) previously produced
     * **silent empty recordings** rather than an error when the mode could not deliver.
     *
     * Only ever switches things OFF, and only things the mode cannot do — so returning to standalone
     * cannot silently re-enable something the user had deliberately turned off. They stay off until the
     * user asks for them again, which is the safe direction to be wrong in.
     */
    fun disableWhatModeCannotDo(mode: PrivilegedMode): Set<ModeCapability> {
        val turnedOff = mutableSetOf<ModeCapability>()
        val marks = getStringSet(Key.MODE_AUTO_DISABLED).toMutableSet()

        gatedSwitches().forEach { gated ->
            if (gated.capability.isAvailableIn(mode) || !gated.isOn()) return@forEach
            gated.set(false)
            // Remember that WE turned this one off, so [restoreWhatModeCanDoAgain] can undo exactly
            // this and nothing else. Recorded per switch rather than per capability: VoIP recording and
            // VoIP auto-start share a capability, and restoring them together would hand auto-start to
            // someone who only ever wanted the prompt.
            marks += gated.id
            turnedOff += gated.capability
        }

        if (turnedOff.isNotEmpty()) setStringSet(Key.MODE_AUTO_DISABLED, marks)
        return turnedOff
    }

    /**
     * Turns back on what *this app* turned off, once [mode] can honour it again, and reports what came
     * back.
     *
     * The counterpart to [disableWhatModeCannotDo], and the reason a mode round trip is no longer a
     * one-way door. Trying Shizuku once and coming straight back used to leave resilient recording,
     * VoIP recording and offline recording off for good, with nothing on screen saying so — recording
     * still worked, so there was no symptom, and the user simply lost the setup they had chosen.
     *
     * **Only ever restores switches recorded by [disableWhatModeCannotDo]**, which by construction were
     * on immediately before it turned them off. So the safety property that made this one-way in the
     * first place still holds exactly: a switch the *user* turned off is never touched, because it was
     * never recorded. A restored switch's record is consumed, so a later reconcile cannot resurrect
     * something the user turned off in the meantime.
     */
    fun restoreWhatModeCanDoAgain(mode: PrivilegedMode): Set<ModeCapability> {
        val marks = getStringSet(Key.MODE_AUTO_DISABLED)
        if (marks.isEmpty()) return emptySet()

        val restored = mutableSetOf<ModeCapability>()
        val remaining = marks.toMutableSet()

        gatedSwitches().forEach { gated ->
            // Still impossible in this mode? Keep the record rather than dropping it — an app start in
            // Shizuku mode must be a no-op, not an amnesia.
            if (gated.id !in marks || !gated.capability.isAvailableIn(mode)) return@forEach
            gated.set(true)
            remaining -= gated.id
            restored += gated.capability
        }

        if (restored.isNotEmpty()) setStringSet(Key.MODE_AUTO_DISABLED, remaining)
        return restored
    }

    /**
     * One user-facing switch a privileged mode can make impossible, and how to read and write it.
     *
     * The single table both [disableWhatModeCannotDo] and [restoreWhatModeCanDoAgain] walk, so the two
     * directions cannot drift apart — a switch added to one is automatically handled by the other.
     */
    private class GatedSwitch(
        /** Stable across preference renames: it is persisted, so it must never change. */
        val id: String,
        val capability: ModeCapability,
        val isOn: () -> Boolean,
        val set: (Boolean) -> Unit,
    )

    private fun gatedSwitches(): List<GatedSwitch> = listOf(
        GatedSwitch(
            "handoff_persist", ModeCapability.RESILIENT_RECORDING,
            ::isHandoffPersistEnabled, ::setHandoffPersistEnabled,
        ),
        GatedSwitch(
            "voip_recording", ModeCapability.VOIP_RECORDING,
            ::isVoipRecordingEnabled, ::setVoipRecordingEnabled,
        ),
        GatedSwitch(
            "voip_auto_start", ModeCapability.VOIP_RECORDING,
            ::isVoipAutoStartEnabled, ::setVoipAutoStartEnabled,
        ),
        GatedSwitch(
            "offline_recording", ModeCapability.OFFLINE_RECORDING,
            ::isOfflineRecordingEnabled, ::setOfflineRecordingEnabled,
        ),
        GatedSwitch(
            "persistent_server", ModeCapability.DAEMON_KEEP_ALIVE,
            ::isPersistentServerEnabled, ::setPersistentServerEnabled,
        ),
        GatedSwitch(
            "wd_disable_when_idle", ModeCapability.WIRELESS_DEBUGGING_CONTROL,
            ::isWdDisableWhenIdle, ::setWdDisableWhenIdle,
        ),
    )

    /**
     * Whether the privileged transport this mode uses has been set up.
     *
     * **Not the same question as "is it paired".** Pairing is standalone's answer; a Shizuku user never
     * pairs anything and would otherwise read as permanently unfinished — which is exactly what kept the
     * boot receiver, the app-start warmup and onboarding from ever running in Shizuku mode.
     *
     * Deliberately a *persisted* fact rather than a live check, matching what `isAdbPaired` was used for:
     * a live connection is per-process and would force re-onboarding on every launch.
     */
    fun isPrivilegedTransportSetUp(): Boolean = when (getPrivilegedMode()) {
        PrivilegedMode.STANDALONE -> isAdbPaired()
        // The user chose Shizuku explicitly; whether its server happens to be running right now is a
        // liveness question, answered separately by SetupPrerequisites and the status card.
        PrivilegedMode.SHIZUKU -> true
    }

    // -------- Who is who on a transcript --------

    /**
     * The user's own answer to which channel is the far party, as a `ChannelMap` key.
     *
     * Outranks everything the app works out for itself. Null means nothing has been said, and the
     * guess stands.
     */
    fun getSpeakerMapOverride(): String? = getString(Key.SPEAKER_MAP_OVERRIDE, null)

    fun setSpeakerMapOverride(key: String?) = setString(Key.SPEAKER_MAP_OVERRIDE, key)

    /**
     * Whether the user has ever answered the "is this right?" offer.
     *
     * Confirming and correcting both count: either way they have looked, and the transcript stops
     * asking. Kept apart from the override so that agreeing with the guess is also an answer.
     */
    fun getSpeakerMapConfirmed(): Boolean = getBoolean(Key.SPEAKER_MAP_CONFIRMED, false)

    fun setSpeakerMapConfirmed(confirmed: Boolean) = setBoolean(Key.SPEAKER_MAP_CONFIRMED, confirmed)

    // -------- App lock --------

    /**
     * Whether opening the app requires the device unlock.
     *
     * Off by default. On is the safer setting, but a lock nobody asked for on an app somebody already
     * relies on reads as a malfunction the first time it appears — and
     * [com.baba.callvault.system.AppLock.isEnabled] gates this on the phone actually having a screen
     * lock, so a stored `true` is a request rather than a guarantee.
     */
    fun isAppLockEnabled(): Boolean = getBoolean(Key.APP_LOCK_ENABLED, false)

    fun setAppLockEnabled(enabled: Boolean) = setBoolean(Key.APP_LOCK_ENABLED, enabled)

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

    /**
     * Apps whose calls must NOT be recorded.
     *
     * Exclusions, not an allow-list, and the direction matters: an allow-list stored literally
     * starts out empty, and an empty allow-list means "record nothing" — every existing user would
     * silently stop recording app calls on upgrade. Empty here means "record everything", which is
     * both the safe default and the behaviour before the setting existed.
     */
    fun getVoipExcludedPackages() = getStringSet(Key.VOIP_EXCLUDED_PACKAGES)

    /** Replaces the set of apps whose calls are not recorded. */
    fun setVoipExcludedPackages(packages: Set<String>) = setStringSet(Key.VOIP_EXCLUDED_PACKAGES, packages)

    /** Whether to write a BCR-compatible `.json` details file beside each recording. */
    fun isWriteMetadataFileEnabled() = getBoolean(Key.WRITE_METADATA_FILE, DefaultsValue.WRITE_METADATA_FILE)

    /** Sets whether a `.json` details file is written beside each recording. */
    fun setWriteMetadataFileEnabled(enabled: Boolean) = setBoolean(Key.WRITE_METADATA_FILE, enabled)

    /** Discard finished recordings shorter than this many seconds (0 = keep every recording). */
    fun getMinDurationSeconds() = getInt(Key.MIN_DURATION_SECONDS, DefaultsValue.MIN_DURATION_SECONDS)

    /** Sets the shortest recording worth keeping, in seconds (0 = keep every recording). */
    fun setMinDurationSeconds(seconds: Int) = setInt(Key.MIN_DURATION_SECONDS, seconds)

    /** Delete the oldest device recordings once the library exceeds this many bytes (0 = no cap). */
    fun getStorageCapBytes() = getLong(Key.STORAGE_CAP_BYTES, DefaultsValue.STORAGE_CAP_BYTES)

    /** Sets the on-device size cap in bytes (0 = no cap). */
    fun setStorageCapBytes(bytes: Long) = setLong(Key.STORAGE_CAP_BYTES, bytes)

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
    /**
     * Whether CallVault handles carrier calls at all.
     *
     * This is the master switch that makes "app calls only" a real mode rather than a combination
     * users have to infer. Turning the two auto-record switches off does NOT achieve it: that is the
     * "Ask me" state, and CallVault still posts a standby notification with a Record button on every
     * single phone call. With this off, a carrier call is ignored end to end — no recording, no
     * notification, nothing on the status card.
     *
     * Defaults to **on**, so no existing install changes behaviour; the quiet mode is opted into.
     */
    fun isCarrierRecordingEnabled() = getBoolean(Key.CARRIER_RECORDING_ENABLED, DefaultsValue.CARRIER_RECORDING_ENABLED)
    fun setCarrierRecordingEnabled(enabled: Boolean) = setBoolean(Key.CARRIER_RECORDING_ENABLED, enabled)

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
    /**
     * The logcat ring size (KiB) as it was before debug logging grew it, or null when it was never
     * successfully read.
     *
     * Null is load-bearing: restoring a size we only guessed at would shrink a buffer the user or the
     * OEM had deliberately enlarged, so an unreadable reading leaves the ring alone instead.
     */
    fun getLogcatRingPreviousKib(): Int? =
        getInt(Key.LOGCAT_RING_PREVIOUS_KIB, -1).takeIf { it > 0 }

    fun setLogcatRingPreviousKib(kib: Int?) {
        prefs.edit {
            if (kib == null) remove(Key.LOGCAT_RING_PREVIOUS_KIB.id)
            else putInt(Key.LOGCAT_RING_PREVIOUS_KIB.id, kib)
        }
    }

    fun isLoggingEnabled() = getBoolean(Key.LOGGING_ENABLED, DefaultsValue.LOGGING_ENABLED)

    /**
     * Whether the Wireless debugging switch that is currently on was turned on by CallVault.
     *
     * Persisted rather than held in memory because the answer has to survive the app being killed:
     * without it, the first ADB operation after a restart would treat a switch the user flipped as
     * ours and turn it off — which is the whole of #30.
     */
    fun wasWirelessDebuggingEnabledByUs() = getBoolean(Key.WD_ENABLED_BY_US, false)

    /** Records who turned Wireless debugging on. See [wasWirelessDebuggingEnabledByUs]. */
    fun setWirelessDebuggingEnabledByUs(byUs: Boolean) = setBoolean(Key.WD_ENABLED_BY_US, byUs)

    /**
     * Whether the user switched Wireless debugging off themselves — not Android (Wi-Fi dropped, or a refused
     * write) and not us. See [com.baba.callvault.integrations.adb.WirelessDebuggingOffCause]. While true, and
     * unless [isWirelessDebuggingEnforced], CallVault does not switch it back on by itself.
     */
    fun wasWirelessDebuggingTurnedOffByUser() = getBoolean(Key.WD_TURNED_OFF_BY_USER, false)

    fun setWirelessDebuggingTurnedOffByUser(byUser: Boolean) = setBoolean(Key.WD_TURNED_OFF_BY_USER, byUser)

    /**
     * The opt-in "keep Wireless debugging on for recording" setting. Off by default (decided 2026-09-14): a
     * switch the user turned off stays off, and the notification says recording is paused.
     */
    fun isWirelessDebuggingEnforced() = getBoolean(Key.WD_ENFORCED, false)

    fun setWirelessDebuggingEnforced(enforced: Boolean) = setBoolean(Key.WD_ENFORCED, enforced)

    /** Sets whether logging features are enabled. */
    fun setLoggingEnabled(enabled: Boolean) = setBoolean(Key.LOGGING_ENABLED, enabled)

    /**
     * This install's random salt for the contact-name pseudonyms in the diagnostic log
     * ([com.baba.callvault.utils.LogRedaction.contactToken]). Generated once on first read and
     * persisted, like [getLoopbackAdbPort].
     *
     * It must be per-install and secret. A bare hash of a contact name is not a pseudonym: the space
     * of names people save in a phone is tiny, so "Mum" or "Dad" falls to a dictionary immediately,
     * and the debug report is written to be attached to a **public** GitHub issue. Salting makes the
     * tokens stable for one user — so their own log lines and their own past reports still correlate
     * — and meaningless to anyone comparing one user's report with another's.
     *
     * Losing it (uninstall/clear-data) only means new tokens differ from old ones. That is fine: it
     * wipes the log those tokens appeared in at the same time.
     */
    fun getLogPseudonymSalt(): String {
        getString(Key.LOG_PSEUDONYM_SALT)?.takeIf { it.isNotBlank() }?.let { return it }
        val bytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val salt = bytes.joinToString("") { "%02x".format(it) }
        setString(Key.LOG_PSEUDONYM_SALT, salt)
        return salt
    }

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

    /**
     * Chooses the audio codec and brings a workable bit rate with it — issue #28c.
     *
     * **Why this is not just [setAudioCodec].** A bit rate that suits one codec need not suit another:
     * Opus is fine at 24 kbps, while AAC-LC at 48 kHz mono / 24 kbps is where hardware encoders start
     * refusing to configure, and a refused `configure()` used to mean a call that recorded nothing at
     * all. Settings adopted the new codec's recommended rate; the onboarding wizard did not, so a codec
     * chosen there kept the previous codec's rate. Both call this now.
     *
     * Only a real CHANGE of codec moves the rate — re-picking what is already selected leaves a rate the
     * user deliberately chose exactly where it is. An unrecognised key changes nothing at all.
     */
    fun chooseAudioCodec(codecKey: String) {
        val codec = runCatching { ScrcpyAudioCodec.fromKey(codecKey) }.getOrNull() ?: return
        if (getAudioCodec() == codec.cliKey) return
        setAudioCodec(codec.cliKey)
        setAudioBitRate(codec.defaultBitRate)
    }

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

    /**
     * The raw key of the section of Home the user was last in, or null if they have never been
     * anywhere.
     *
     * Returns the string rather than a [HomeSection] on purpose. This value is written by whatever
     * build the user was last on, so it can name a section that no longer exists — deciding what to
     * do about that is [HomeSection.opening]'s job, and it cannot decide anything about a value
     * already silently turned into a default on the way out of here.
     */
    fun getLastHomeSectionKey(): String? = getString(Key.LAST_HOME_SECTION)

    /** Remembers the section of Home the user is in, so the next visit can reopen it. */
    fun setLastHomeSection(section: HomeSection) = setString(Key.LAST_HOME_SECTION, section.key)

    /** Checks if toast notifications are enabled. */
    fun isShowToastsEnabled() = getBoolean(Key.SHOW_TOASTS, DefaultsValue.SHOW_TOASTS)

    /** Sets whether toast notifications are enabled. */
    fun setShowToastsEnabled(enabled: Boolean) = setBoolean(Key.SHOW_TOASTS, enabled)

}
