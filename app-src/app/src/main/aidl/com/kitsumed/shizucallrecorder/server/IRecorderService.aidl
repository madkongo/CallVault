/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.server;

/**
 * CallVault Plan 5 — PRODUCTION recorder command channel.
 *
 * Implemented by the detached, privileged shell-uid recorder daemon
 * ([com.kitsumed.shizucallrecorder.server.RecorderServer]) and called BY THE APP over a raw binder
 * (no ADB), even while Wireless debugging is OFF. The daemon runs scrcpy-server + the muxer; the APP
 * owns metadata (SAF filename, call-log lookups) and merely hands the daemon a writable output fd.
 *
 * KISS simplification vs the Plan 5 draft signature: recording metadata stays APP-side, so the
 * interface only carries what the daemon needs (source/codec/bitRate + the output fd). The app names
 * the SAF file and performs call-log lookups itself, exactly as
 * [com.kitsumed.shizucallrecorder.services.recording.AudioRecordingEngine] does today.
 *
 * Mirrors the proven spike interface [com.kitsumed.shizucallrecorder.persistserver.IPersistDebugService]
 * and Shizuku's IShizukuService command-channel pattern.
 */
interface IRecorderService {

    /**
     * Starts a recording session. The daemon launches scrcpy-server with the given [source]/[codec]/
     * [bitRate] and muxes the captured audio into [outFd] (a writable fd the APP opened from its SAF
     * file — the privileged daemon writes through it). No-op + rejected if already recording.
     *
     * @param source  scrcpy `audio_source` cliKey (ScrcpyAudioSource.cliKey, e.g. "voice-call").
     * @param codec   scrcpy `audio_codec` cliKey (ScrcpyAudioCodec.cliKey, e.g. "opus").
     * @param bitRate Encoder bit rate in bps.
     * @param outFd   Writable output file descriptor opened by the APP from its SAF recording file.
     */
    void startRecording(String source, String codec, int bitRate, in ParcelFileDescriptor outFd);

    /** Stops the active recording, finalising the container trailer. Idempotent. */
    void stopRecording();

    /** Returns true while a recording session is active. */
    boolean isRecording();

    /** Stops any active recording and terminates the daemon process. */
    void destroy();
}
