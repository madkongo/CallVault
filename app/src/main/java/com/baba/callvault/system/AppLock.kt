/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system

import android.app.KeyguardManager
import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import com.baba.callvault.data.AppPreferences

/**
 * Whether opening CallVault should require the device's own unlock.
 *
 * **Why this exists now and did not before.** The app used to hold audio files, which a file manager
 * could see anyway. It now holds a searchable full-text record of every conversation, plus a written
 * summary of what each one decided — a materially different thing to hand someone who picks the phone
 * up unlocked. No user asked for this; the reason is the change in what is behind the door.
 *
 * **The lock is the device's, not ours.** [DEVICE_CREDENTIAL] alongside [BIOMETRIC_WEAK] means the PIN,
 * pattern or password already protecting the phone is what opens the app, with a fingerprint or face as
 * the convenient path to the same decision. CallVault stores no secret of its own, so it has nothing
 * better to check against and no business inventing a second one to forget.
 *
 * 🚨 **The recordings must never become unreachable.** The lock is a door in front of the UI, not
 * encryption: the audio stays where the user put it and remains readable by a file manager and by
 * whatever syncs it. That is deliberate — a lock that could strand somebody's call history would be a
 * worse failure than the exposure it prevents. [isAvailable] exists for the same reason: on a phone
 * with no screen lock at all there is nothing to authenticate against, so the setting refuses to turn
 * on rather than shutting the door with no key.
 */
object AppLock {

    /**
     * What we ask for: a weak biometric **or** the device credential.
     *
     * `BIOMETRIC_WEAK` rather than `BIOMETRIC_STRONG` because this gates a screen rather than a
     * cryptographic key — requiring strong-class hardware would exclude perfectly ordinary phones from
     * a convenience while adding nothing, since the credential fallback is always accepted anyway.
     */
    private const val ALLOWED = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /**
     * Whether this phone can authenticate at all.
     *
     * False when there is no screen lock set up, which is the case that matters: enabling the setting
     * there would lock the app with a key that does not exist.
     *
     * `BIOMETRIC_STATUS_UNKNOWN` is treated as available. "None enrolled" is available only when the
     * keyguard is secure: with the device credential allowed, that status means no screen lock at all.
     */
    fun isAvailable(context: Context): Boolean {
        val status = BiometricManager.from(context).canAuthenticate(ALLOWED)
        // Read the keyguard only for the one status it settles (C78); any other status is decided alone.
        val deviceSecure = if (status == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
            runCatching { context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure }
                .getOrNull() ?: true
        } else {
            true
        }
        return canLockWith(status, deviceSecure)
    }

    /**
     * Which `BiometricManager` statuses mean the app can be locked, separated out so the decision can
     * be tested against every status rather than against whichever one this phone reports.
     *
     * The three that mean **no** are the ones the user cannot resolve from here: no hardware and no
     * credential, a security update the app cannot install, and an unsupported configuration. Every
     * other status — including "none enrolled" and "unknown" — is treated as yes, because the device
     * credential still answers and the user can enrol later without the setting having silently
     * turned itself off in the meantime.
     */
    internal fun canLockWith(status: Int, deviceSecure: Boolean = true): Boolean = when (status) {
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
        BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
        BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> false
        // With DEVICE_CREDENTIAL in the set the framework answers NONE_ENROLLED only when the phone
        // has no screen lock at all: no key for the door (C78). Needs the keyguard's word to be sure.
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> deviceSecure
        else -> true
    }

    /** Whether the user has asked for the app to be locked **and** the phone can honour it. */
    fun isEnabled(context: Context): Boolean =
        AppPreferences(context).isAppLockEnabled() && isAvailable(context)

    /**
     * Whether the current visit counts as unlocked after the Settings toggle changed (C76).
     * Switching the lock on from the open app is the owner being present, so it stays unlocked until
     * the next visit; switching it off changes nothing here.
     */
    internal fun unlockedAfterToggle(nowEnabled: Boolean, wasUnlocked: Boolean): Boolean =
        nowEnabled || wasUnlocked

    /** The authenticator set to pass to a `BiometricPrompt.PromptInfo`. */
    fun allowedAuthenticators(): Int = ALLOWED
}
