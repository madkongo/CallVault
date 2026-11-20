/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

/**
 * Whether USB debugging is on — with the third answer the old boolean could not give.
 *
 * The boolean it replaces defaulted to `false`, so "we could not read it" and "the user switched it off"
 * were the same value. That is what made Android 17 so damaging: the setting started lying `0` to every
 * app, the app believed it, and ten call sites concluded the phone was broken.
 */
enum class UsbDebuggingState {
    ON,
    OFF,

    /** Neither proven. Never treated as OFF, and never told to the user as a fact. */
    UNKNOWN;

    /** True only when it is *proven* on. A caller may act on this. */
    val isOn: Boolean get() = this == ON

    /**
     * True only when it is *proven* off. A caller may tell the user this and may blame it for a failure.
     * Deliberately not `!isOn` — the whole point is that the unknown case is neither.
     */
    val isOff: Boolean get() = this == OFF
}

/**
 * Works out [UsbDebuggingState] from the setting plus corroboration.
 *
 * ## Why not just check the Android version
 *
 * Android 17 redacts `Settings.Global.ADB_ENABLED` to `0` for every third-party app
 * (`docs/dev-notes/2026-09-19-android-17-adb-detection-issue-40.md`). The obvious fix — and the only one
 * anyone in the ecosystem has shipped — is "treat a 0 as unknown when `SDK_INT >= 37`". **That is not
 * safe.** Android 16 custom ROMs backport the redaction and ship the aconfig flag baked on, so the same
 * lie arrives while `Build.VERSION.SDK_INT` reads 36. Nor is the redaction CTS-enforced, so OEMs may
 * differ on 17 itself. A version number cannot answer this question.
 *
 * It is also a worse answer even where it works, because a blanket "unknown" throws away the ability to
 * tell a user whose USB debugging really *is* off what to switch on.
 *
 * ## What is used instead
 *
 * Two measured rules from `docs/dev-notes/2026-09-14-debugging-switches-model.md`, and no version check:
 *
 * - **R1** — `adbd` runs while USB debugging **or** Wireless debugging is on. So `adbd` up with Wireless
 *   debugging off is a *proof* that USB debugging is on, whatever the setting claims.
 * - **R2** — turning USB debugging off stops `adbd` even while Wireless debugging still reads on. So a
 *   stopped `adbd` is evidence about USB debugging whichever way Wireless reads.
 *
 * `init.svc.adbd` is readable by every app on Android 17 — re-verified against `android17-release`
 * sepolicy (`private/domain.te:534`) — and is not redacted, so the corroborator survives the change that
 * broke the setting.
 *
 * Free of `Context` so every branch is tested.
 */
object UsbDebuggingPolicy {

    /**
     * @param settingSaysOn the raw `Settings.Global.ADB_ENABLED` read, `== 1`. A `1` is always the truth:
     *   the redaction only ever substitutes a zero, never a one.
     * @param adbd Android's own `init.svc.adbd`.
     * @param wirelessDebuggingOn `adb_wifi_enabled`, which Android 17 leaves truthful.
     */
    fun of(
        settingSaysOn: Boolean,
        adbd: AdbdState,
        wirelessDebuggingOn: Boolean,
        /**
         * The setting as the shell uid read it, when that is fresh ([UsbDebuggingShellRead]); null when unknown.
         * The redaction exempts uids below the first app uid, so this is the truth whichever way it reads.
         */
        shellSaysOn: Boolean? = null,
    ): UsbDebuggingState = when {
        settingSaysOn -> UsbDebuggingState.ON
        shellSaysOn == true -> UsbDebuggingState.ON
        shellSaysOn == false -> UsbDebuggingState.OFF
        // R1: something keeps adbd up, and it is not Wireless debugging.
        adbd == AdbdState.RUNNING && !wirelessDebuggingOn -> UsbDebuggingState.ON
        // R1/R2: nothing is holding adbd up, so USB debugging is not on. Requires the setting to agree,
        // which costs nothing (on a redacting build it always says off) and keeps Android <=16 exactly as
        // it behaves today.
        adbd == AdbdState.STOPPED -> UsbDebuggingState.OFF
        // adbd RUNNING with Wireless debugging also on: Wireless alone explains it, so USB could be
        // either. adbd UNKNOWN ("restarting", or unreadable): no evidence at all.
        else -> UsbDebuggingState.UNKNOWN
    }

    /**
     * What answers USB debugging in this state, so the keep-alive pays for a shell read ([UsbDebuggingShellRead]) —
     * a fork+exec in the recorder — only where nothing cheaper does:
     *
     * - the setting reads 1: [ShellReadNeed.SETTING_DECIDES], always ON;
     * - adbd RUNNING with Wireless debugging off: [ShellReadNeed.R1_DECIDES], ON. The keep-alive records that proof
     *   with a shell read's freshness, so a brief adbd stop (screen-off restarts on OnePlus/Xiaomi/Samsung) still
     *   answers ON, exactly as the last per-tick shell read did.
     * - otherwise [ShellReadNeed.READ]: Wireless debugging on (it alone can keep adbd up — the Honor 2026-11-04 "off for
     *   two hours" case), adbd STOPPED (R2's off and a restart look alike), or adbd unreadable.
     *
     * UNVERIFIED: R1 with the setting at 0 assumes nothing but USB debugging keeps adbd up while Wireless debugging is
     * off. Measured on the emulator and the OP9 only; not on Samsung, and not with another app's tcpip or Shizuku TCP
     * listener holding adbd up. If one does, the shell read (0) would have said OFF where R1 now says ON.
     */
    fun shellReadNeed(settingSaysOn: Boolean, adbd: AdbdState, wirelessDebuggingOn: Boolean): ShellReadNeed = when {
        settingSaysOn -> ShellReadNeed.SETTING_DECIDES
        adbd == AdbdState.RUNNING && !wirelessDebuggingOn -> ShellReadNeed.R1_DECIDES
        else -> ShellReadNeed.READ
    }
}

/** What decides USB debugging in a state, from [UsbDebuggingPolicy.shellReadNeed]. */
enum class ShellReadNeed {
    /** The app's own setting reads 1: the truth, no read needed. */
    SETTING_DECIDES,

    /** adbd up with Wireless debugging off: R1 proves ON, no read needed. */
    R1_DECIDES,

    /** Only the recorder's (shell uid) read can tell. */
    READ,
}
