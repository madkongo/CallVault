/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import android.content.Context
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.PrivilegedMode
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.utils.AppLogger

/**
 * The device's **Default USB Configuration** (Developer options → "Default USB Configuration") — i.e.
 * the USB functions applied when the screen is unlocked (`screen_unlocked_functions`).
 *
 * **Why CallVault cares.** On OnePlus/Xiaomi/Samsung a DATA default (File transfer, etc.) makes the USB
 * gadget renegotiate on every screen on/off transition, which **restarts `adbd`** — and that kills the
 * shell-uid recorder daemon. If it happens mid-call (user locks the phone) the recording stops. Setting
 * the default to **"No data transfer / Charging only"** (no screen-unlocked functions) removes that
 * churn, so the daemon survives a screen lock and the recording continues. Confirmed on-device.
 *
 * Read via `dumpsys usb` and changed via `svc usb setScreenUnlockedFunctions <fn>`, both over the app's
 * embedded ADB shell (shell uid holds the privilege). Both are framework-level → OEM-agnostic.
 */
enum class UsbDefaultMode(
    /** Argument to `svc usb setScreenUnlockedFunctions` ("" = charging/off). */
    val svcArg: String,
) {
    /** "No data transfer / Charging only" — RECOMMENDED; the recorder survives a screen lock. */
    CHARGING(""),
    /** "File transfer / Android Auto" (MTP). */
    FILE_TRANSFER("mtp"),
    /** "PTP". */
    PTP("ptp"),
    /** "USB tethering" (RNDIS). */
    TETHERING("rndis"),
    /** "MIDI". */
    MIDI("midi"),
    /**
     * "Debugging only" — One UI 8 / Android 16's new default, which carries no data function.
     *
     * Safe for the same reason [CHARGING] is: nothing renegotiates when the screen unlocks. Recognised
     * because issue #22 showed the cost of not recognising it — an unknown value is treated as "we
     * don't know", which silences the advice this screen exists to give.
     */
    DEBUGGING_ONLY("adb"),
    /** Couldn't read / unrecognised value. */
    UNKNOWN(""),
}

/**
 * What happened to a request to change the Default USB Configuration.
 *
 * More than a boolean because the two failures need different words in front of the user: one is
 * "this mode cannot do that", the other is "not now, you are on a call".
 */
enum class UsbSetResult {
    /** The command was delivered to the device. */
    APPLIED,

    /** Refused: a recording is live, and applying this would restart adbd and end it. */
    BUSY_RECORDING,

    /** Refused: there is no embedded ADB shell to apply it through (Shizuku mode), or the mode is UNKNOWN. */
    UNAVAILABLE,
}

/** What, if anything, the user should be told about the current Default USB Configuration. */
enum class UsbNotice {
    /** Nothing to say: a known-safe mode. */
    NONE,

    /** A data mode is set — locking the screen mid-call can kill the recorder. */
    DATA_MODE_RISK,

    /** We could not read the setting, and the recorder is not coming up. Worth suggesting. */
    COULD_NOT_CHECK,
}

object UsbDefaultConfig {

    private const val TAG = "CV:UsbDefault"

    /** The only mode in which recording reliably survives a mid-call screen lock. */
    val RECOMMENDED = UsbDefaultMode.CHARGING

    /** Modes offered to the user in the picker (UNKNOWN is never a choice). */
    val SELECTABLE = listOf(
        UsbDefaultMode.CHARGING,
        UsbDefaultMode.DEBUGGING_ONLY,
        UsbDefaultMode.FILE_TRANSFER,
        UsbDefaultMode.PTP,
        UsbDefaultMode.TETHERING,
        UsbDefaultMode.MIDI,
    )

    /** Modes in which nothing renegotiates on a screen transition, so the daemon survives one. */
    private val SAFE = setOf(UsbDefaultMode.CHARGING, UsbDefaultMode.DEBUGGING_ONLY)

    /**
     * Reads the current Default USB Configuration over the ADB shell (`dumpsys usb`). Returns null when
     * there is no live shell to read through (caller should fall back to [cached]); does NOT force a
     * connection, so it never causes WD/adbd churn. Caches the value on success. Call OFF the main thread.
     */
    fun readViaShell(context: Context): UsbDefaultMode? =
        parseAndCache(context, runShell(context, READ_CMD, ensure = true)) ?: readFromSystemProperty(context)

    /**
     * Like [readViaShell] but reads ONLY if an ADB connection is already up — never forces one, so it
     * causes no WD/adbd churn. Use for opportunistic cache refreshes on paths that already hold a
     * connection (e.g. right after a daemon launch). Returns null when nothing was read. OFF main thread.
     */
    fun readIfConnected(context: Context): UsbDefaultMode? =
        parseAndCache(context, runShell(context, READ_CMD, ensure = false)) ?: readFromSystemProperty(context)

    /**
     * Falls back to the `sys.usb.config` system property, for ROMs whose `dumpsys usb` does not print
     * the setting at all.
     *
     * **Why this exists.** On a OnePlus 12 (ColorOS) `dumpsys usb` omits `screen_unlocked_functions`
     * whenever no data function is set, so the read above returns nothing on a correctly-configured
     * phone. That is the *safe* case, but the app could not tell it from a failed shell, so the mode
     * stayed UNKNOWN for ever and the readiness notification kept offering to check something it never
     * could. The property resolves it.
     *
     * **What the property actually is, measured 2026-08-04.** It holds the USB functions *currently
     * applied*, which in the steady state are the screen-unlocked ones. Sampled across a deliberate
     * unplug: `adb` while a manual override was in force for the cable, then `rndis,none,adb` once the
     * cable was out and the system re-applied the configured functions — the tethering mode that was
     * genuinely set. It does not blank out when disconnected, which was the failure this had to rule out;
     * a blank would have mapped to "no data functions" and reported SAFE for a phone that was not.
     *
     * Two known imprecisions, both harmless for the warning this drives. While someone has manually
     * overridden the mode to transfer files, it reports the override rather than the stored default — but
     * an override is itself a data mode, so warning then is if anything correct. And a value of `adb`
     * alone cannot distinguish "Charging only" from One UI's "Debugging only"; both are safe, so only the
     * label shown in the picker is a guess.
     *
     * Needs no shell, no daemon and no ADB connection — unlike everything else in this file.
     */
    private fun readFromSystemProperty(context: Context): UsbDefaultMode? {
        val raw = systemProperty(USB_CONFIG_PROP)
        val mode = parseProperty(raw)
        if (mode == UsbDefaultMode.UNKNOWN) return null
        AppPreferences(context).setUsbDefaultMode(mode.name)
        AppLogger.i(TAG, "Default USB Configuration from $USB_CONFIG_PROP='$raw': $mode")
        return mode
    }

    /**
     * Classifies a `sys.usb.config` value (a comma-separated function list, e.g. `rndis,none,adb`).
     *
     * A blank value means the property is absent — no evidence, so [UsbDefaultMode.UNKNOWN] rather than a
     * cheerful "no data functions". `none` is the opposite: positive evidence that nothing is configured.
     */
    internal fun parseProperty(raw: String): UsbDefaultMode {
        val v = raw.trim().lowercase()
        if (v.isEmpty()) return UsbDefaultMode.UNKNOWN
        return when {
            v.contains("mtp") -> UsbDefaultMode.FILE_TRANSFER
            v.contains("ptp") -> UsbDefaultMode.PTP
            v.contains("rndis") -> UsbDefaultMode.TETHERING
            v.contains("midi") -> UsbDefaultMode.MIDI
            // Only adb and/or none: no data function is applied, which is the safe case. Which safe mode
            // it is cannot be told from here — see the note above.
            else -> UsbDefaultMode.CHARGING
        }
    }

    /** Reads a system property via the hidden `SystemProperties.get` (reflection; public SDK-safe). */
    private fun systemProperty(key: String): String = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, key) as? String ?: ""
    }.getOrDefault("")

    private const val USB_CONFIG_PROP = "sys.usb.config"

    private fun parseAndCache(context: Context, out: String?): UsbDefaultMode? {
        if (out == null) return null
        // Filter for the relevant line in Kotlin rather than a `| grep` (pipes are fragile over `shell:`).
        val line = out.lineSequence().firstOrNull { it.contains("screen_unlocked_functions") }
        if (line == null) {
            // The command ran and the field is not in the output. On a OnePlus 12 (ColorOS) that turned
            // out to mean "no screen-unlocked functions are set" rather than "this ROM never prints it":
            // with Charging only the line is absent entirely, and the moment the mode became USB
            // tethering the same command printed `screen_unlocked_functions=RNDIS`. So absence is weak
            // evidence of the SAFE case, not of an unreadable device — worth logging either way, because
            // a silent null here is indistinguishable from a shell that failed.
            AppLogger.i(TAG, "dumpsys usb printed no screen_unlocked_functions line (often means none is set)")
            return null
        }
        val mode = parse(line)
        if (mode != UsbDefaultMode.UNKNOWN) AppPreferences(context).setUsbDefaultMode(mode.name)
        AppLogger.i(TAG, "Default USB Configuration read: $mode (raw: '${line.trim()}')")
        return mode
    }

    /** The last successfully-read value (persisted), for UI shown while no shell is available. */
    fun cached(context: Context): UsbDefaultMode =
        resolveCached(AppPreferences(context).getUsbDefaultMode(), privilegedMode(context))

    /**
     * What a [stored] mode name means, given the [mode] the app is running in.
     *
     * **Shizuku mode always resolves to [UsbDefaultMode.UNKNOWN], and that is the fix for a real
     * complaint.** Both [readViaShell] and [setViaShell] refuse to run there — there is no embedded
     * ADB — so anything stored was written under a different mode and can never be refreshed or
     * corrected. The app nevertheless warned from it, about a setting its own picker declines to
     * change, leaving the user told to fix something the app would not let them fix. Issue #28's
     * reporter hit exactly that, and switching the setting by hand stopped Shizuku.
     *
     * UNKNOWN is not a new rule; it is what "we cannot see this" already means throughout this file,
     * and [noticeFor] already treats it sensibly — silent while the recorder is up, and an honest
     * "could not check" when it is not.
     */
    fun resolveCached(stored: String?, mode: PrivilegedMode): UsbDefaultMode {
        if (!mode.needsAdbSetup) return UsbDefaultMode.UNKNOWN
        return runCatching { UsbDefaultMode.valueOf(stored ?: "") }.getOrDefault(UsbDefaultMode.UNKNOWN)
    }

    /**
     * True when the cached Default USB Configuration is a DATA mode (File transfer, etc.) — i.e. locking
     * the screen mid-call may restart adbd and kill the recorder. Safe modes and UNKNOWN return false, so
     * we never warn without a confirmed data value.
     */
    fun isScreenLockRisk(context: Context): Boolean = cached(context).let { it !in SAFE && it != UsbDefaultMode.UNKNOWN }

    /**
     * What to tell the user, given the USB mode we last read and whether the recorder is up.
     *
     * **Why [recorderReady] is an input, and why it no longer gates everything.** The warning used to be
     * shown only when the recorder was ready. That inverted it: a data USB mode kills the daemon, a dead
     * daemon means not-ready, and not-ready hid the very warning that explained the death. Issue #22's
     * reporter sat on "Connecting the recorder…" across two calls and was never told about the setting.
     * A risk is now stated whether or not the recorder is up — it is more urgent when it is not.
     *
     * [UsbNotice.COULD_NOT_CHECK] is the other half of that lesson. An unreadable setting used to resolve
     * to silence, indistinguishable to the user from "checked, you're fine". It is now said out loud —
     * but only while the recorder is failing to come up, so a working phone is never nagged about a
     * check that did not matter.
     */
    fun noticeFor(mode: UsbDefaultMode, recorderReady: Boolean): UsbNotice = when {
        mode in SAFE -> UsbNotice.NONE
        mode == UsbDefaultMode.UNKNOWN -> if (recorderReady) UsbNotice.NONE else UsbNotice.COULD_NOT_CHECK
        else -> UsbNotice.DATA_MODE_RISK
    }

    /**
     * Re-reads the setting over a fresh, retrying shell, but ONLY when nothing was ever read.
     *
     * The opportunistic refresh on the recording path gets one un-retried attempt over a connection that
     * is busy starting a recording; on the device in issue #22 it failed with "Stream closed" and the
     * value stayed unknown forever. This is the calm path — called when the daemon has just come up, with
     * nothing else competing for the shell. No-op once a value is known, so it costs one read per install
     * in the normal case. Call OFF the main thread.
     */
    fun readIfUnknown(context: Context): UsbDefaultMode? {
        if (cached(context) != UsbDefaultMode.UNKNOWN) return null
        return readViaShell(context)
    }

    /**
     * Sets the Default USB Configuration over the ADB shell (ensures a connection first). Returns true if
     * the command was delivered.
     *
     * NOTE: switching to [UsbDefaultMode.CHARGING] drops USB *data* (including USB-adb) until the user
     * picks a data mode again — harmless in normal wireless/loopback use, but it means a cable plugged
     * into a PC defaults to charging. Call OFF the main thread.
     */
    fun setViaShell(context: Context, mode: UsbDefaultMode): UsbSetResult {
        if (mode == UsbDefaultMode.UNKNOWN) return UsbSetResult.UNAVAILABLE
        // Refused rather than merely skipped: [runShell] would drop the command anyway, but the cache
        // write below would still record an intent the device never received — and a wrong cache here
        // is what the picker shows back to the user. See [isShellUsable] for why nothing runs.
        if (!isShellUsable(privilegedMode(context))) {
            AppLogger.i(TAG, "Shizuku mode: not setting the Default USB Configuration — no embedded ADB")
            return UsbSetResult.UNAVAILABLE
        }
        // NEVER during a recording. Applying this renegotiates the USB gadget, which restarts adbd and
        // kills the shell-uid daemon holding the capture — measured on the OP9 on 2026-09-05, where one
        // change gave adbd a new pid and killed the shell-uid process hosted by the old one. Off a call
        // that costs a few seconds of reconnect; during one it ends the recording, and this setting
        // exists to PREVENT recordings being lost.
        if (isRecordingLive()) {
            AppLogger.w(TAG, "Not setting the Default USB Configuration: a recording is live and this restarts adbd")
            return UsbSetResult.BUSY_RECORDING
        }
        // `svc` applies the change ON-DEVICE even when its (empty) response stream closes early, so the
        // stream result cannot be trusted either way. Fire it and record the intent.
        //
        // Renegotiating the USB gadget restarts adbd (the reason for the recording guard above), and that
        // also kills any Shizuku server — which is not ours and which nothing restarts. The picker gives
        // no warning of its own, so the user is told afterwards which app it stopped.
        AdbdChurnNotice.around(context, "changing the Default USB Configuration") {
            runShell(context, "svc usb setScreenUnlockedFunctions ${mode.svcArg}".trimEnd(), ensure = true)
        }
        AppPreferences(context).setUsbDefaultMode(mode.name)
        AppLogger.i(TAG, "Set Default USB Configuration to $mode")
        return UsbSetResult.APPLIED
    }

    /**
     * Whether the daemon says it is recording right now. A daemon we cannot reach answers "no": it is
     * not recording anything either, and a setting the user asked for should not be refused on the
     * strength of a question we could not ask.
     */
    private fun isRecordingLive(): Boolean =
        runCatching { RecorderConnection.service?.isRecording == true }.getOrDefault(false)

    // There used to be a confirming read-back here. It was removed on 2026-08-04 because it could not
    // succeed and cost the user a long spinner for nothing:
    //
    //  - Choosing "Charging only" severs USB data, and USB-adb with it. The confirmation then ran over
    //    the transport the write had just killed, burning two attempts with an 8-second connect budget
    //    each plus a reconnect before giving up. The device applied the change instantly; the app sat
    //    "Applying…" for tens of seconds. Reported from the device.
    //  - Its verdict was `readback == null || readback == mode` — i.e. only a *different* answer counted
    //    as failure. Once the property fallback existed that became actively harmful: `sys.usb.config`
    //    still reports the previously applied functions for a moment after a switch, so a stale reply
    //    would compare unequal and the picker would silently refuse to show what the user just chose.
    //
    // A wrong cache is self-correcting — the next successful read overwrites it — whereas a wrong "that
    // didn't work" in front of the user is not.

    /**
     * Classifies a `screen_unlocked_functions=` line. Data functions win: the value is a comma-separated
     * list, and `mtp,adb` is a file-transfer default that happens to include adb — still a data mode.
     */
    internal fun parse(dumpsysLine: String): UsbDefaultMode {
        val v = dumpsysLine.substringAfter("screen_unlocked_functions=", "").trim().lowercase()
        return when {
            v.contains("mtp") -> UsbDefaultMode.FILE_TRANSFER
            v.contains("ptp") -> UsbDefaultMode.PTP
            v.contains("rndis") -> UsbDefaultMode.TETHERING
            v.contains("midi") -> UsbDefaultMode.MIDI
            // adb alone carries no data function — One UI 8's "Debugging only".
            v == "adb" -> UsbDefaultMode.DEBUGGING_ONLY
            v.isEmpty() || v == "none" -> UsbDefaultMode.CHARGING
            else -> UsbDefaultMode.UNKNOWN
        }
    }

    /**
     * Runs [cmd] over the embedded ADB shell and returns its stdout. The wireless/loopback link is
     * flaky ("Stream closed"), so retry once with a fresh connection — mirroring the daemon launcher.
     * Returns null if both attempts fail. Call OFF the main thread.
     */
    private fun runShell(context: Context, cmd: String, ensure: Boolean): String? = when {
        // No embedded ADB in Shizuku mode, so the command cannot succeed — and reaching for it is not
        // free. See [isShellUsable]: the wizard's reliability step ran this probe on entry and that was
        // enough to switch Wireless debugging on for a user who had never paired anything.
        !isShellUsable(privilegedMode(context)) -> {
            AppLogger.i(TAG, "Shizuku mode: skipping '$cmd' — no embedded ADB, and WD must stay untouched")
            null
        }
        // Held for the whole retry loop, not per attempt: this probe is what was measured leaving
        // Wireless debugging on indefinitely on the OP9, because nothing after it ever switched it off.
        // Only the connecting path takes the lease. The opportunistic read (`ensure = false`) never
        // connects, so it can never turn Wireless debugging on and has nothing to release — and taking
        // the lease there LEAKED it: that read is run on a bounded worker thread that is deliberately
        // abandoned when the ADB stream wedges (a documented, observed hang), so the abandoned thread
        // held a lease nobody could ever return. Measured on the OP9: every subsequent release logged
        // `last=false`, no release ever ran, and `adb_wifi_enabled` sat at 1 in Shizuku mode.
        !ensure -> runShellInner(context, cmd, ensure)
        else -> AdbShell.asAdbUser(context, "the USB-default probe") { runShellInner(context, cmd, ensure) }
    }

    /**
     * Whether the embedded ADB shell may be driven at all in [mode].
     *
     * Shizuku mode never pairs, so nothing here can succeed there — but `ensureConnected` is the app's
     * ONLY writer of `adb_wifi_enabled = 1`, so the failing attempt still opens a network port. That is
     * the same hole the log collector had (fixed 2026-08-25 in `SystemLogCollector.runShell`), and this
     * is deliberately the same fix: skip the shell entirely rather than trust every caller to check.
     *
     * Losing the reading is cheap — [readFromSystemProperty] needs no ADB and still answers on most
     * ROMs — and the advice it drives is about *our* daemon surviving adbd churn, which is not the
     * failure mode of a Shizuku-hosted recorder anyway.
     */
    internal fun isShellUsable(mode: PrivilegedMode): Boolean = !mode.needsShizuku

    private fun privilegedMode(context: Context): PrivilegedMode = AppPreferences(context).getPrivilegedMode()

    private fun runShellInner(context: Context, cmd: String, ensure: Boolean): String? {
        val attempts = if (ensure) 2 else 1
        repeat(attempts) { attempt ->
            val connected = when {
                !ensure -> AdbConnectionManager.getInstance(context).isConnected // opportunistic: never force
                attempt == 0 -> AdbShell.ensureConnected(context)
                else -> AdbShell.forceReconnect(context)
            }
            if (!connected) return@repeat
            val out = runCatching {
                AdbShell.openShell(context, cmd).use { s -> s.openInputStream().use { String(it.readBytes()) } }
            }.onFailure { AppLogger.d(TAG, "USB shell cmd attempt ${attempt + 1} failed ('$cmd'): ${it.message}") }
                .getOrNull()
            if (out != null) return out
        }
        if (ensure) AppLogger.w(TAG, "USB shell cmd failed after retry ('$cmd')")
        return null
    }

    private const val READ_CMD = "dumpsys usb"
}
