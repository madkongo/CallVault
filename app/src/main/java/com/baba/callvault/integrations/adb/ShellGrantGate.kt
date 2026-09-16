/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

/**
 * Whether the phone lets the shell hand CallVault the privilege it needs — and, when it does not, whose
 * wording to show.
 *
 * **The thing being detected.** Several OEMs strip privileged permissions from uid 2000 unless a hidden
 * Developer-options switch is on. ColorOS/OxygenOS/realmeUI calls it **"Disable permission monitoring"**,
 * renamed **"Disable system optimization"** on versions 15-16; Xiaomi's is **"USB debugging (Security
 * settings)"**; Meizu's is "Flyme payment protection"; vivo blocks a narrower set. With the switch off,
 * `pm grant`, `appops set` and `settings put` all raise SecurityException for the shell.
 *
 * **What it costs us.** Only the grant. Measured on the OP9 on 2026-09-16 (see
 * `docs/dev-notes/2026-09-16-oem-adb-restrictions-and-onboarding-check.md`): a permission granted earlier
 * keeps working, survives a reboot and survives an install-over, the app's own secure-settings writes are
 * unaffected, a running daemon keeps running, and `pm install` from the shell still works. So this blocks
 * **setting CallVault up**, and nothing else — which is why the advice belongs in onboarding and why a user
 * who is already granted must never be nagged about it.
 *
 * **Why the detection is shaped like this.** There is no "can the shell grant?" query: `cmd package
 * check-permission` does not exist on our targets, and `dumpsys package com.android.shell` still reports the
 * permission as granted while the phone is refusing it (the OEM intercepts the check, not the record). The
 * ColorOS property is exact where it exists but is OPPO-only and Xiaomi's equivalent has the opposite
 * polarity — so the portable answer is to try the grant and look at what actually happened.
 */
object ShellGrantGate {

    /** What we know about the shell's ability to grant CallVault its privilege. */
    enum class ShellGrantState {
        /** The shell may grant — either measured, or the OEM property says so. */
        ALLOWED,

        /** The phone is refusing grants from the shell: the user has a switch to flip. */
        BLOCKED,

        /** No evidence either way. Most phones have no gate and never produce evidence at all. */
        UNKNOWN,
    }

    /** Whose switch to name in the instructions. */
    enum class OemGate { OPPO, XIAOMI, VIVO, MEIZU, OTHER }

    /** The ColorOS/OxygenOS/realmeUI property. `true` = permission monitoring is ON = the shell is blocked. */
    const val OPPO_PROPERTY = "persist.sys.permission.enable"

    /** Xiaomi's equivalent, with the OPPOSITE polarity (`1` = permissive). Read only to recognise the phone. */
    const val XIAOMI_PROPERTY = "persist.security.adbinput"

    /**
     * Reads the OPPO property, which is the one place a phone states this outright.
     *
     * Polarity proven on the OP9 by flipping the UI switch and re-reading: ON (what we need) → `false`,
     * OFF (the shipped default) → `true`. Absent on every non-OPPO ROM, hence [ShellGrantState.UNKNOWN].
     */
    fun fromOemProperty(value: String?): ShellGrantState = when (value?.trim()) {
        "true" -> ShellGrantState.BLOCKED
        "false" -> ShellGrantState.ALLOWED
        else -> ShellGrantState.UNKNOWN
    }

    /**
     * Reads the outcome of an actual `pm grant`, which works on any phone.
     *
     * [permissionHeldAfter] is the only proof of success: Xiaomi has been reported to print nothing and grant
     * nothing, so a silent run is NOT evidence of anything. Only the OEM's own SecurityException names a gate;
     * anything else unproven stays [ShellGrantState.UNKNOWN] rather than blaming a switch that may be fine.
     */
    fun fromGrantAttempt(output: String, permissionHeldAfter: Boolean): ShellGrantState = when {
        permissionHeldAfter -> ShellGrantState.ALLOWED
        output.contains("SecurityException") ||
            output.contains("does not have") ||
            output.contains("nor current process has") -> ShellGrantState.BLOCKED
        else -> ShellGrantState.UNKNOWN
    }

    /**
     * Which OEM's instructions to show. The property is checked first because it is the phone's own
     * statement; the manufacturer name is the fallback for a ROM that hides or lacks it.
     */
    fun oemGate(manufacturer: String, oemProperty: String?, miuiProperty: String?): OemGate {
        if (!oemProperty.isNullOrBlank()) return OemGate.OPPO
        if (!miuiProperty.isNullOrBlank()) return OemGate.XIAOMI
        return when (manufacturer.lowercase()) {
            "oppo", "oneplus", "realme" -> OemGate.OPPO
            "xiaomi", "redmi", "poco" -> OemGate.XIAOMI
            "vivo", "iqoo" -> OemGate.VIVO
            "meizu" -> OemGate.MEIZU
            else -> OemGate.OTHER
        }
    }

    /**
     * Whether to tell the user to go and change a switch.
     *
     * Blocked but already granted is the normal state of a working phone that was set up before the user
     * ever saw this screen — the grant survives the switch going back off — so it is not worth a word.
     */
    fun shouldAdvise(state: ShellGrantState, permissionHeld: Boolean): Boolean =
        state == ShellGrantState.BLOCKED && !permissionHeld
}
