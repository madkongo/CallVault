/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.recording

import android.app.Service
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.kitsumed.shizucallrecorder.R
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.data.recordings.RecordingMetadata
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyAudioCodec
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyAudioMuxer
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyAudioSource
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyClient
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ScrcpyLauncher
import com.kitsumed.shizucallrecorder.server.RecorderConnection
import com.kitsumed.shizucallrecorder.server.RecorderServerLauncher
import com.kitsumed.shizucallrecorder.system.storage.SafHelper
import com.kitsumed.shizucallrecorder.integrations.scrcpy.ServerExtractor
import com.kitsumed.shizucallrecorder.utils.AppLogger
import com.kitsumed.shizucallrecorder.utils.RecordingFileNameFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Manages the audio recording pipeline, including the connection to the ADB transport, reading from the audio pipe,
 * parsing scrcpy-server custom stream format, and writing to the output container via [ScrcpyAudioMuxer].
 *
 * Call [startPipeline] to initialize and start the recording, and [release] to clean up resources when done.
 */
class AudioRecordingEngine {

    companion object {
        private const val TAG = "SCR:AudioRecordingEngine"
    }

    /**
     * Parses the raw byte stream that arrives from the shell process pipe.
     *
     * Calls the attached callbacks with parsed audio packets and stream metadata.
     */
    var scrcpyClient: ScrcpyClient? = null

    /** Writes scrcpy decoded audio packets into the output container (OPUS/AAC). */
    var scrcpyAudioMuxer: ScrcpyAudioMuxer? = null

    /** Metadata captured during the [startPipeline] and locked. Used for checks in [release] if we need to query call logs for the final file name if phone number is empty. */
    var initializationMetadata: RecordingMetadata? = null
        set(value) {
            if (field == null) {
                field = value
            } else {
                AppLogger.w(TAG, "Attempt to overwrite recording session metadata ignored. THIS SHOULD NOT HAPPEN. Original: $field, New: $value")
            }
        }

    /**
     * Active [ScrcpyLauncher] instance that owns the ADB shell stream and audio socket.
     * Created in [startPipeline] and stopped in [release].
     */
    private var scrcpyLauncher: ScrcpyLauncher? = null

    /**
     * Write-access file descriptor for the output file.
     * This is kept open for the duration of the recording so [ScrcpyAudioMuxer] can write to it,
     * and is closed in [release] after the muxer finalizes the container header.
     */
    var outputPfd: ParcelFileDescriptor? = null

    /**
     * URI of the current recording file.
     * Used to delete the file if recording fails to start mid-initialization.
     */
    var currentRecordingUri: Uri? = null

    /**
     * Active codec enum resolved from the user's preference and confirmed by the stream header.
     * Updated once [ScrcpyClient.AudioPacketListener.onMetadataReceived] fires.
     * Defaults to [ScrcpyAudioCodec.OPUS] as a safe initial value before the stream header is read.
     */
    var currentCodecEnum: ScrcpyAudioCodec = ScrcpyAudioCodec.OPUS

    /**
     * Coroutine scope for reading from the audio pipe data returned by the ADB transport.
     * Initialised in [startPipeline] and cancelled in [release].
     */
    var audioPipeReadScope: CoroutineScope? = null

    /**
     * The active pipe reading job.
     * We keep a reference so we can wait to finish reading any late bytes during [release].
     */
    var audioPipeReadJob: Job? = null

    /**
     * Whether this session is recording via the persistent privileged daemon (CallVault Plan 5) instead
     * of the local scrcpy pipeline. Decided ONCE in [startPipeline] from
     * [AppPreferences.isPersistentServerEnabled] (OFF by default). When false the engine behaves exactly
     * as before; when true the daemon owns scrcpy + muxing and the local muxer/launcher/scope are never
     * created, so [release] must skip their teardown.
     */
    private var daemonMode: Boolean = false

    /**
     * Whether the recording is currently paused by the user.
     *
     * In [daemonMode] this is a no-op for the pipeline: the daemon writes straight into the output fd,
     * so app-side pause cannot drop packets (acceptable v1 gap — see [setter]). In local mode the audio
     * reader honours this flag to skip writing to the muxer while paused.
     */
    @Volatile
    var isPaused: Boolean = false
        set(value) {
            if (daemonMode && value) {
                // Log once on the transition into a (no-op) paused state in daemon mode.
                AppLogger.w(TAG, "pause not supported in persistent-server mode")
            }
            field = value
        }

    /**
     * Orchestrates the initialization and connection of the entire recording pipeline.
     * @throws PipelineInitializationException if any step of the initialization fails, with details for user-friendly and technical error reporting.
     */
    fun startPipeline(context: Service, metadata: RecordingMetadata) {
        initializationMetadata = metadata
        val preferences = AppPreferences(context)
        val folderUri = preferences.getRecordingFolderUri()

        if (!SafHelper.isFolderValid(context, folderUri)) {
            throw PipelineInitializationException(
                userFriendlyMessage = context.getString(R.string.recording_error_folder_missing),
                technicalLogMessage = "Cannot start recording: Selected Output folder is missing, invalid, or we do not have permission to write to it"
            )
        }

        val codecEnum = ScrcpyAudioCodec.fromKey(preferences.getAudioCodec())
        val bitRate = preferences.getAudioBitRate().takeIf { it > 0 } ?: codecEnum.defaultBitRate
        val audioSourceEnum = ScrcpyAudioSource.fromKey(preferences.getAudioSource())

        AppLogger.i(TAG, "Starting recording pipeline: source=${audioSourceEnum.cliKey} codec=${codecEnum.cliKey} bitrate=$bitRate")

        val fileName = RecordingFileNameFormatter.formatFileName(context, metadata, codecEnum)

        val safResult = SafHelper.createAudioFile(context, folderUri, fileName, codecEnum.mimeType)
            ?: throw PipelineInitializationException(
                userFriendlyMessage = context.getString(R.string.recording_error_file_creation),
                technicalLogMessage = "Failed to create audio file in SAF storage"
            )

        AppLogger.d(TAG, "Created SAF recording file: ${safResult.uri}")

        currentRecordingUri = safResult.uri
        outputPfd = safResult.descriptor

        // GATED branch (CallVault Plan 5): when the persistent-server flag is ON, hand the SAF output fd
        // to the privileged daemon and let IT own scrcpy + muxing. The pfd creation above is identical
        // for both paths; only the consumer differs. When the flag is OFF, the local path below runs
        // completely unchanged.
        if (preferences.isPersistentServerEnabled()) {
            daemonMode = true
            startDaemonPipeline(context, audioSourceEnum, codecEnum, bitRate)
            currentCodecEnum = codecEnum
            return
        }

        scrcpyAudioMuxer = ScrcpyAudioMuxer(outputPfd!!.fileDescriptor, safResult.displayName)

        val launcher = try {
            ScrcpyLauncher.start(context, audioSourceEnum, codecEnum, bitRate)
        } catch (e: Exception) {
            throw PipelineInitializationException(
                userFriendlyMessage = e.localizedMessage ?: context.getString(R.string.recording_error_start_failed),
                technicalLogMessage = "ScrcpyLauncher.start failed",
                cause = e,
            )
        }
        scrcpyLauncher = launcher

        currentCodecEnum = codecEnum
        scrcpyAudioMuxer?.initialize(currentCodecEnum)

        scrcpyClient = ScrcpyClient(
            input = launcher.audioInput,
            expectedCodec = codecEnum,
            listener = object : ScrcpyClient.AudioPacketListener {
                /**
                 * Called once after the 4-byte codec FourCC is verified from the stream header.
                 * We re-initialise the muxer with the confirmed codec in case it differs from our initial assumption.
                 */
                override fun onMetadataReceived(codec: ScrcpyAudioCodec) {
                    AppLogger.d(TAG, "Stream metadata confirmed: codec=${codec.cliKey} fourCC=0x${codec.codecFourCC.toString(16)}")
                    currentCodecEnum = codec
                    scrcpyAudioMuxer?.initialize(codec)
                }

                /** Called for every audio frame received from the pipe. */
                override fun onAudioPacket(packet: ScrcpyClient.AudioPacket) {
                    if (isPaused) return // Drop packets while paused, do not write to muxer
                    scrcpyAudioMuxer?.writePacket(packet, currentCodecEnum)
                }

                /** Called when the stream ends normally (EOF) or with an error. */
                override fun onStreamEnd(error: String?) {
                    if (error != null) {
                        AppLogger.w(TAG, "Scrcpy-client reported stopping parsing due to an audio stream error: $error")
                    } else {
                        AppLogger.d(TAG, "Scrcpy-client reported our pipe read stream ended normally (EOF)")
                    }
                }
            }
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        audioPipeReadScope = scope
        audioPipeReadJob = scope.launch(Dispatchers.IO) {
            try {
                scrcpyClient?.start()
            } catch (e: Exception) {
                AppLogger.w(TAG, "Audio reader ended: ${e.message}")
            }
        }
    }

    /**
     * Persistent-server pipeline (CallVault Plan 5): ensure the privileged daemon is running, then hand
     * it the already-opened SAF output fd and the resolved source/codec/bitRate. The daemon owns scrcpy
     * + muxing into [outputPfd]; the engine creates NO local muxer/launcher/client/scope in this mode.
     *
     * @throws PipelineInitializationException if the daemon cannot be reached or rejects the start.
     */
    private fun startDaemonPipeline(
        context: Service,
        audioSourceEnum: ScrcpyAudioSource,
        codecEnum: ScrcpyAudioCodec,
        bitRate: Int
    ) {
        AppLogger.i(TAG, "Persistent-server mode: ensuring recorder daemon is running")
        val connected = RecorderServerLauncher.ensureServerRunning(context)
        val service = RecorderConnection.service
        if (!connected || service == null) {
            throw PipelineInitializationException(
                userFriendlyMessage = context.getString(R.string.recording_error_start_failed),
                technicalLogMessage = "Persistent recorder server unavailable (connected=$connected, service=${service != null})"
            )
        }

        try {
            service.startRecording(audioSourceEnum.cliKey, codecEnum.cliKey, bitRate, outputPfd)
        } catch (e: Exception) {
            throw PipelineInitializationException(
                userFriendlyMessage = context.getString(R.string.recording_error_start_failed),
                technicalLogMessage = "Daemon startRecording failed",
                cause = e,
            )
        }
        AppLogger.i(TAG, "Persistent-server mode: daemon startRecording dispatched, daemon now owns scrcpy + muxing")
    }

    /**
     * Safely releases all held resources in the correct order.
     * Everything is wrapped in runCatching to ignore any exceptions and continue the cleanup.
     *
     * In [daemonMode] (CallVault Plan 5) the daemon owns scrcpy + muxing, so release only asks the daemon
     * to stop and closes the local fd handle; the local muxer/launcher/client/scope were never created so
     * their teardown is skipped. The metadata/call-log finalize done by [RecordingForegroundService] after
     * release() is unaffected (it reads [initializationMetadata]/[currentRecordingUri]/[currentCodecEnum],
     * none of which release() clears).
     *
     * Local mode (flag off) is unchanged:
     * 1. Stops the ScrcpyLauncher (closes ADB shell and audio streams), which gives scrcpy-server
     *    a grace period to write its final audio bytes before closing the pipe from the sender side.
     * 2. Waits for the local reading coroutine to reach EOF and finish parsing the late bytes.
     * 3. Cancels the active reading coroutine and scrcpy client as a fallback.
     * 4. Closes the muxer and output file descriptor to finalize the container header.
     */
    fun release() {
        AppLogger.i(TAG, "Releasing session resources and recording pipeline...")

        if (daemonMode) {
            // Ask the daemon to stop + finalise the container, then close our local fd handle. The local
            // muxer/launcher/client/scope do not exist in this mode, so there is nothing else to tear down.
            runCatching { RecorderConnection.service?.stopRecording() }
                .onFailure { AppLogger.w(TAG, "Daemon stopRecording failed during release: ${it.message}") }
            runCatching { outputPfd?.close() }
            return
        }

        runCatching { scrcpyLauncher?.stop() }
        scrcpyLauncher = null

        runCatching {
            runBlocking {
                withTimeoutOrNull(2000L) {
                    audioPipeReadJob?.join()
                }
            }
        }

        runCatching { scrcpyClient?.stop() }
        runCatching { audioPipeReadScope?.cancel() }
        runCatching { scrcpyAudioMuxer?.close() }
        runCatching { outputPfd?.close() }
    }

    /**
     * Trigger the normal [release] flow, then followed by an attempt to delete the incomplete recording file if it was created
     * during the pipeline initialization.
     */
    fun cancel(context: Context) {
        release()
        try {
            currentRecordingUri?.let { uri ->
                DocumentFile.fromSingleUri(context, uri)?.delete()
            }
            AppLogger.d(TAG, "Cleaned up empty file after start failure")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to cleanup empty file", e)
        }
    }
}

/**
 * Custom exception to carry a user-friendly message for UI display
 * and a technical log message for debugging when the pipeline initialization fails.
 */
class PipelineInitializationException(
    val userFriendlyMessage: String,
    technicalLogMessage: String,
    cause: Throwable? = null
) : Exception(technicalLogMessage, cause)
