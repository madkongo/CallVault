/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

/**
 * What CallVault does when a call starts — the whole decision, in one place and free of Android.
 *
 * Both kinds of call have the same three states, and until now each was missing a different one:
 *
 * |               | Off                   | Ask me                   | Automatic            |
 * |---------------|-----------------------|--------------------------|----------------------|
 * | Phone calls   | *was missing*         | auto-record off          | auto-record on       |
 * | App calls     | VoIP recording off    | *was missing*            | VoIP recording on    |
 *
 * The phone-call gap was the surprising one. Turning both auto-record switches off looks like "don't
 * record my phone calls", but it is the **Ask me** state: CallVault still posts a standby
 * notification with a Record button on every single call. Someone recording only app calls got
 * prompted about every phone call they took, which is what made a real Off state necessary rather
 * than a documentation fix.
 *
 * Kept pure so the matrix can be tested exhaustively without a device: this is the code that decides
 * whether a call is recorded at all, and a mistake in it is silent — you find out afterwards, from
 * the recording that does not exist.
 */
internal object RecordingPolicy {

    /** What should happen to a carrier call. */
    enum class CarrierAction {
        /** Do nothing at all: no capture, no notification. "App calls only". */
        IGNORE,

        /** Offer to record it — the standby notification with its Record action. */
        OFFER,

        /** Start recording without asking. */
        RECORD,
    }

    /** What should happen to an app (VoIP) call. */
    enum class VoipAction {
        /** Not watching for app calls at all. */
        IGNORE,

        /** Detected, but the user decides — show the prompt. */
        OFFER,

        /** Start recording without asking. */
        RECORD,
    }

    /**
     * @param carrierEnabled The master switch. False means phone calls are not CallVault's business.
     * @param autoRecord     Whether the per-direction rules (direction switch, plus the anonymous /
     *                       cross-country / ignored-contact filters) chose to record **this** call.
     */
    fun forCarrierCall(carrierEnabled: Boolean, autoRecord: Boolean): CarrierAction = when {
        !carrierEnabled -> CarrierAction.IGNORE
        autoRecord -> CarrierAction.RECORD
        else -> CarrierAction.OFFER
    }

    /**
     * @param voipEnabled Whether app-call recording is switched on at all.
     * @param autoStart   Within that, whether a detected call starts recording by itself.
     */
    fun forVoipCall(voipEnabled: Boolean, autoStart: Boolean): VoipAction = when {
        !voipEnabled -> VoipAction.IGNORE
        autoStart -> VoipAction.RECORD
        else -> VoipAction.OFFER
    }

    /**
     * Whether a carrier call the user did *not* get should count against the setup's health.
     *
     * The status card sweeps the call log for calls that should have been recorded and were not. In
     * "app calls only" mode every phone call is such a call by construction, so without this the card
     * would fill with gaps for calls deliberately ignored — turning a feature into an alarm.
     */
    fun expectsCarrierRecording(carrierEnabled: Boolean, autoRecordForDirection: Boolean): Boolean =
        carrierEnabled && autoRecordForDirection

    /**
     * Whether the carrier path should stand aside because an app call owns this one.
     *
     * The carrier pipeline is started by the phone-state broadcast, which fires for app calls too —
     * so on One UI a WhatsApp call had it create a second recording, named from the call log (an
     * unrelated contact) and empty, because the daemon was already busy with the real one. Standing
     * down fixed that.
     *
     * The danger is standing down for a call that is genuinely the carrier's, because then nobody
     * records it and the user finds out afterwards, from the recording that does not exist. That is
     * why [carrierCallUp] is consulted and why it wins over the audio mode: an IMS or Wi-Fi call can
     * itself sit in `MODE_IN_COMMUNICATION`, which the mode check alone reads as an app call.
     *
     * @param voipEnabled           app-call recording switched on at all. Off means this whole rule
     *                              is inert — the behaviour before app calls existed.
     * @param voipRecording         the app-call path already has this call.
     * @param carrierCallUp         Telecom reports a call from a *managed* ConnectionService, which
     *                              an app call never is. See [VoipTelephonyGate].
     * @param modeIsInCommunication the audio mode says an app call, covering the ~230 ms in which
     *                              telephony fires before the app call has been detected.
     */
    /**
     * Review N29: with app-call recording OFF, whether this phone-call start is for a call that looks self-managed
     * (Meet, Slack, WhatsApp on One UI): Telecom is readable and reports no managed call, yet the audio mode is
     * the app-call one. Only ever NAMED, in the always-on log; the carrier recording still starts. Standing down
     * would lose a real carrier call on any phone whose Telecom answers wrongly at the instant of the off-hook,
     * which cannot be ruled out without testing on many phones. The recording that results is judged by the
     * silence check (N28), so a silent one is flagged rather than stored as Verified.
     */
    fun startsForSelfManagedCall(
        voipEnabled: Boolean,
        telecomReadable: Boolean,
        managedCallUp: Boolean,
        modeIsInCommunication: Boolean,
    ): Boolean = !voipEnabled && telecomReadable && !managedCallUp && modeIsInCommunication

    fun standsDownForAppCall(
        voipEnabled: Boolean,
        voipRecording: Boolean,
        carrierCallUp: Boolean,
        modeIsInCommunication: Boolean
    ): Boolean = when {
        !voipEnabled -> false
        // A real carrier call is recorded even while an app call is being recorded (review C1). The app-call
        // recording is ended by its own path when the phone call is answered (VoipCallDetector.abortForCarrierCall,
        // which runs on the main thread before this service's start is handled); standing down here as well
        // left the phone call recorded by nobody.
        carrierCallUp -> false
        voipRecording -> true
        else -> modeIsInCommunication
    }
}
