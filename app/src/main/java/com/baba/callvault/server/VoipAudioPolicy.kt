/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.baba.callvault.utils.AppLogger
import java.lang.reflect.Constructor

/**
 * Taps the FAR PARTY of a VoIP call by registering a dynamic audio policy.
 *
 * A VoIP app renders the remote voice as an ordinary playback track tagged `USAGE_VOICE_COMMUNICATION`.
 * Registering an [android.media.audiopolicy.AudioMix] that matches that usage, routed
 * `ROUTE_FLAG_LOOP_BACK_RENDER`, duplicates the stream into a submix we can read — while it keeps
 * playing normally, so the user still hears the call. (Plain `ROUTE_FLAG_LOOP_BACK`, i.e. capturing
 * `REMOTE_SUBMIX`, makes the mix the PRIMARY output instead and the device goes silent — which is why
 * every previous attempt at VoIP capture through the submix was abandoned as unusable.)
 *
 * **ARM EARLY.** Android fixes a track's routing when the track is CREATED, so the policy must already
 * be registered before the call's audio track exists. Registering mid-call does not merely clip the
 * start — the call is never attached to the mix and the whole recording is silent. Hence [arm] is
 * called when the feature is switched on, not when a call begins. The SINK may be created later:
 * [createSink] works fine on a call already in progress.
 *
 * Arming is free: measured on-device, an armed policy with no sink holds no wakelock and no active
 * record track, so it does not keep the device awake.
 *
 * All of this is hidden API reached by reflection, and every entry point is best-effort — a device
 * whose OEM removed or changed it simply reports unavailable and the feature stays off.
 */
internal object VoipAudioPolicy {
    private const val TAG = "CV:VoipPolicy"

    // Pinned from AOSP (AudioMix.java / AudioMixingRule.java).
    private const val MIX_ROLE_PLAYERS = 0                  // = AudioMix.MIX_TYPE_PLAYERS
    private const val RULE_MATCH_ATTRIBUTE_USAGE = 0x1
    private const val ROUTE_FLAG_LOOP_BACK_RENDER = 0x3     // LOOP_BACK | RENDER — capture AND keep playing

    const val SAMPLE_RATE = 48_000

    @Volatile private var policy: Any? = null
    @Volatile private var mix: Any? = null

    /** True while a policy is registered and a sink can be created. */
    val isArmed: Boolean get() = policy != null

    /**
     * Registers the loopback mix. Idempotent. Returns false when the device or its OEM build does not
     * permit it — most often because `com.android.shell` lacks `CAPTURE_VOICE_COMMUNICATION_OUTPUT`,
     * which is the real gate (the builder's own `voiceCommunicationCaptureAllowed` flag is advisory and
     * gets overwritten by the framework from that permission).
     */
    @Synchronized
    fun arm(): Boolean {
        if (policy != null) return true
        return runCatching {
            val ruleCls = Class.forName("android.media.audiopolicy.AudioMixingRule")
            val ruleBuilderCls = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            val rb = ruleBuilderCls.getConstructor().newInstance()
            ruleBuilderCls.getMethod("setTargetMixRole", Int::class.javaPrimitiveType).invoke(rb, MIX_ROLE_PLAYERS)
            ruleBuilderCls.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java).invoke(
                rb, RULE_MATCH_ATTRIBUTE_USAGE,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build(),
            )
            // Advisory — the framework re-derives it from the permission — but set it for parity with
            // the reference implementations.
            runCatching {
                ruleBuilderCls.getMethod("voiceCommunicationCaptureAllowed", Boolean::class.javaPrimitiveType)
                    .invoke(rb, true)
            }
            // NOTE: deliberately NOT allowPrivilegedPlaybackCapture(true). That flag is what triggers
            // canBeUsedForPrivilegedMediaCapture()'s 16 kHz-mono ceiling, and the voice-communication
            // path does not need it — so we get full rate instead of the 16 kHz every published
            // example settles for.
            val rule = ruleBuilderCls.getMethod("build").invoke(rb)

            val mixCls = Class.forName("android.media.audiopolicy.AudioMix")
            val mixBuilderCls = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            val mixCtor: Constructor<*> = mixBuilderCls.getDeclaredConstructor(ruleCls).apply { isAccessible = true }
            val mb = mixCtor.newInstance(rule)
            // The mix describes PLAYBACK, so it takes an OUT channel mask; createAudioRecordSink
            // converts it to an IN mask itself. An IN mask here yields an uninitialised AudioRecord.
            mixBuilderCls.getMethod("setFormat", AudioFormat::class.java).invoke(
                mb,
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            mixBuilderCls.getMethod("setRouteFlags", Int::class.javaPrimitiveType)
                .invoke(mb, ROUTE_FLAG_LOOP_BACK_RENDER)
            val builtMix = mixBuilderCls.getMethod("build").invoke(mb)

            val policyCls = Class.forName("android.media.audiopolicy.AudioPolicy")
            val policyBuilderCls = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
            // A null Context is deliberate and load-bearing. AudioPolicy falls back to
            // AttributionSource.myAttributionSource(), i.e. our own uid with NO package — which is
            // exactly what AudioFlinger's validator (createFromTrustedUidNoPackage) accepts. Obtaining
            // a real Context via ActivityThread.systemMain() instead makes the process claim package
            // "android" under uid 2000, and every record-track creation is then rejected EX_SECURITY.
            val pb = policyBuilderCls.getConstructor(Class.forName("android.content.Context"))
                .newInstance(*arrayOf<Any?>(null))
            policyBuilderCls.getMethod("addMix", mixCls).invoke(pb, builtMix)
            val builtPolicy = policyBuilderCls.getMethod("build").invoke(pb)

            // The static registration needs no AudioManager instance, and therefore no Context.
            val register = Class.forName("android.media.AudioManager")
                .getDeclaredMethod("registerAudioPolicyStatic", policyCls).apply { isAccessible = true }
            val rc = register.invoke(null, builtPolicy) as Int
            if (rc != 0) {
                AppLogger.w(TAG, "registerAudioPolicy rejected (rc=$rc) — CAPTURE_VOICE_COMMUNICATION_OUTPUT missing?")
                return@runCatching false
            }
            policy = builtPolicy
            mix = builtMix
            AppLogger.i(TAG, "VoIP capture policy armed (loopback-render, ${SAMPLE_RATE}Hz)")
            true
        }.onFailure { AppLogger.e(TAG, "arm failed: ${it.message}", it) }.getOrDefault(false)
    }

    /** Unregisters the policy. Idempotent; safe to call when never armed. */
    @Synchronized
    fun disarm() {
        val p = policy ?: return
        policy = null
        mix = null
        runCatching {
            Class.forName("android.media.AudioManager")
                .getDeclaredMethod("unregisterAudioPolicyAsyncStatic", Class.forName("android.media.audiopolicy.AudioPolicy"))
                .apply { isAccessible = true }
                .invoke(null, p)
            AppLogger.i(TAG, "VoIP capture policy disarmed")
        }.onFailure { AppLogger.w(TAG, "disarm failed: ${it.message}") }
    }

    /**
     * Creates the reader for the far-party stream. Valid on a call already in progress — only the
     * POLICY has to predate the call. Returns null when not armed or the sink won't initialise.
     */
    @Synchronized
    fun createSink(): AudioRecord? {
        val p = policy ?: run { AppLogger.w(TAG, "createSink with no armed policy"); return null }
        val m = mix ?: return null
        val normal = runCatching {
            val policyCls = Class.forName("android.media.audiopolicy.AudioPolicy")
            val mixCls = Class.forName("android.media.audiopolicy.AudioMix")
            policyCls.getMethod("createAudioRecordSink", mixCls).invoke(p, m) as AudioRecord?
        }
        val ar = normal.getOrElse { error ->
            if (!VendorConstructorCrash.matches(error)) {
                AppLogger.e(TAG, "createSink failed: ${error.message}", error)
                return null
            }
            // vivo's AudioRecord constructor dereferences a Context the recorder host does not have. Build
            // the very same sink without running that constructor (scrcpy's fix for the identical crash).
            AppLogger.w(TAG, "createSink: the ROM's AudioRecord constructor crashed without a Context " +
                "(${VendorConstructorCrash.rootCause(error).message}); building the sink without it")
            createSinkBypassingConstructor(m) ?: return null
        }
        if (ar == null || ar.state != AudioRecord.STATE_INITIALIZED) {
            AppLogger.w(TAG, "VoIP sink did not initialise (state=${ar?.state})")
            runCatching { ar?.release() }
            return null
        }
        return ar
    }

    /**
     * What `AudioPolicy.createAudioRecordSink` builds — REMOTE_SUBMIX preset, the mix's address tag, fixed
     * volume, the mix's format as an IN mask, a stereo-sized minimum buffer — created through
     * [BypassedAudioRecord] instead of the public constructor. Null (logged) if that fails too.
     */
    private fun createSinkBypassingConstructor(m: Any): AudioRecord? = runCatching {
        val registration = m.javaClass.getMethod("getRegistration").invoke(m) as String
        val format = m.javaClass.getMethod("getFormat").invoke(m) as AudioFormat
        val inMask = AudioFormat::class.java.getMethod("inChannelMaskFromOutChannelMask", Int::class.javaPrimitiveType)
            .invoke(null, format.channelMask) as Int
        BypassedAudioRecord.create(
            capturePreset = MediaRecorder.AudioSource.REMOTE_SUBMIX,
            tags = listOf("addr=$registration", "fixedVolume"),
            sampleRate = format.sampleRate,
            channelMask = inMask,
            channelCount = format.channelCount,
            encoding = format.encoding,
            bufferSizeInBytes = AudioRecord.getMinBufferSize(format.sampleRate, AudioFormat.CHANNEL_IN_STEREO, format.encoding),
        ).also { AppLogger.i(TAG, "VoIP sink built without the vendor constructor (state=${it.state})") }
    }.onFailure { AppLogger.e(TAG, "createSink without the vendor constructor failed too: ${it.message}", it) }.getOrNull()
}
