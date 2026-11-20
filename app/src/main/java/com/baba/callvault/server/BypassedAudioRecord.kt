/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.media.AudioAttributes
import android.media.AudioRecord
import android.os.Binder
import android.os.Build
import android.os.Looper
import android.os.Parcel
import androidx.annotation.RequiresApi
import java.lang.ref.WeakReference

/**
 * Builds an [AudioRecord] without running its public constructor.
 *
 * **Why.** vivo (OriginOS / Funtouch) modified `AudioRecord`'s constructor to call
 * `VivoAudioRecordImpl.isSupportSubMixRecording()`, which dereferences the record's `Context` with no null
 * check. The recorder host has no Context, so on vivo every app-call recording failed at the far-party sink:
 * `NullPointerException … Context.getOpPackageName()` (iQOO V2507A, OriginOS 6, Android 16, 2026-09-28).
 *
 * **Where this comes from.** A port of scrcpy's `Workarounds.createAudioRecord` (Genymobile/scrcpy PR #5154,
 * Apache License 2.0; issues #3805 and #3791 are the same crash, confirmed fixed on vivo). It creates the
 * object through the package-private `AudioRecord(long)` constructor and initialises it by reflection exactly
 * as the public constructor would, so the vendor code never runs.
 *
 * **One difference from scrcpy, on purpose:** the identity. scrcpy passes a stand-in Context for
 * "com.android.shell"; this passes what the public constructor itself uses when it has no Context —
 * `AttributionSource.myAttributionSource()`, renamed `uid:<uid>` when it has no package. That is the identity
 * the far-party sink has always been created with ([VoipAudioPolicy]); anything else was measured to be
 * rejected by AudioFlinger. Also like the public constructor for a REMOTE_SUBMIX capture: the
 * [AudioRecord.SUBMIX_FIXED_VOLUME] tag is not passed down but turned into the full-volume flag.
 *
 * **Android 17** (review N21) replaced the private `mChannelCount` + `mChannelMask` ints with one `mChannelMasks`
 * (`AudioFormat.ChannelMasks`) and passes that object to `native_setup` in place of the two masks. Read from the
 * Android 17 emulator image's framework.jar (build CP41.260831.007): the public constructor builds
 * `new ChannelMasks(positionMask, indexMask, acnMask)` and calls `native_setup(Object, Object, int[], Object, int,
 * int, int[], Parcel, long, int, int)`. Which layout a phone has is read from its fields ([channelLayoutOf]), not
 * assumed from the SDK level. Upstream scrcpy still sets `mChannelCount`, so it has no Android 17 fix to copy.
 *
 * Used only after the normal path has failed — never the first choice.
 *
 * **Hidden APIs, and why lint is told so.** This runs only inside the recorder host — an `app_process` started
 * as the shell user, like scrcpy's server — never in the app process. The hidden-API policy lint enforces for
 * an app targeting API 36 does not govern that host, which already reaches `AudioPolicy` the same way.
 */
@SuppressLint("DiscouragedPrivateApi", "SoonBlockedPrivateApi", "PrivateApi", "BlockedPrivateApi")
internal object BypassedAudioRecord {

    /** The tag the public constructor strips and turns into `mIsSubmixFullVolume`. Hidden constant, same value on 11–17. */
    private const val SUBMIX_FIXED_VOLUME = "fixedVolume"

    /**
     * @param capturePreset the `MediaRecorder.AudioSource` preset the attributes carry.
     * @param tags          attribute tags, e.g. the mix address; [SUBMIX_FIXED_VOLUME] is handled like AOSP does.
     * @param channelMask   an IN channel mask.
     */
    fun create(
        capturePreset: Int,
        tags: List<String>,
        sampleRate: Int,
        channelMask: Int,
        channelCount: Int,
        encoding: Int,
        bufferSizeInBytes: Int,
    ): AudioRecord {
        val cls = AudioRecord::class.java
        val record = cls.getDeclaredConstructor(Long::class.javaPrimitiveType).apply { isAccessible = true }
            .newInstance(0L)

        field("mRecordingState").set(record, AudioRecord.RECORDSTATE_STOPPED)
        field("mInitializationLooper").set(record, Looper.myLooper() ?: Looper.getMainLooper())

        val fullVolume = tags.any { it.equals(SUBMIX_FIXED_VOLUME, ignoreCase = true) }
        if (fullVolume) runCatching { field("mIsSubmixFullVolume").setBoolean(record, true) }

        val ab = AudioAttributes.Builder()
        AudioAttributes.Builder::class.java.getMethod("setInternalCapturePreset", Int::class.javaPrimitiveType)
            .invoke(ab, capturePreset)
        val addTag = AudioAttributes.Builder::class.java.getMethod("addTag", String::class.java)
        tags.filterNot { it.equals(SUBMIX_FIXED_VOLUME, ignoreCase = true) }.forEach { addTag.invoke(ab, it) }
        val attributes = ab.build()
        field("mAudioAttributes").set(record, attributes)

        cls.getDeclaredMethod("audioParamCheck", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(record, capturePreset, sampleRate, encoding)
        // Before audioBuffSizeCheck, which derives the frame size from the channels.
        val channels: Any = when (channelLayoutOf(cls)) {
            ChannelLayout.CHANNEL_MASKS -> channelMasks(channelMask).also { field("mChannelMasks").set(record, it) }
            ChannelLayout.COUNT_AND_MASK -> {
                field("mChannelCount").setInt(record, channelCount)
                field("mChannelMask").setInt(record, channelMask)
                channelMask
            }
        }
        cls.getDeclaredMethod("audioBuffSizeCheck", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(record, bufferSizeInBytes)

        val rates = intArrayOf(sampleRate)
        val session = intArrayOf(android.media.AudioManager.AUDIO_SESSION_ID_GENERATE)
        val nativeBuffer = field("mNativeBufferSizeInBytes").getInt(record)
        val result = nativeSetup(record, attributes, rates, channels, record.audioFormat, nativeBuffer, session)
        check(result == AudioRecord.SUCCESS) { "native_setup returned $result" }

        field("mSampleRate").setInt(record, rates[0])
        field("mSessionId").setInt(record, session[0])
        field("mState").setInt(record, AudioRecord.STATE_INITIALIZED)
        return record
    }

    /** How a given `AudioRecord` class stores its channel configuration. */
    internal enum class ChannelLayout {
        /** Android 17: one `mChannelMasks` (`AudioFormat.ChannelMasks`: position, index and ACN masks plus the count). */
        CHANNEL_MASKS,

        /** Android 11–16: `mChannelCount` and `mChannelMask` ints. */
        COUNT_AND_MASK,
    }

    private const val CHANNEL_MASKS_FIELD = "mChannelMasks"
    private val COUNT_AND_MASK_FIELDS = listOf("mChannelCount", "mChannelMask")

    /**
     * Which [ChannelLayout] [cls] has, read from its declared fields rather than assumed from the SDK level: an OEM
     * ROM can differ from AOSP, and the 2.5.1 code assumed `mChannelCount`, which Android 17 no longer has (review
     * N21, vivo V2546A). The newer field wins if a ROM kept both.
     *
     * @throws NoSuchFieldException naming every field looked for and which were missing.
     */
    internal fun channelLayoutOf(cls: Class<*>): ChannelLayout {
        val declared = cls.declaredFields.mapTo(HashSet()) { it.name }
        if (CHANNEL_MASKS_FIELD in declared) return ChannelLayout.CHANNEL_MASKS
        if (declared.containsAll(COUNT_AND_MASK_FIELDS)) return ChannelLayout.COUNT_AND_MASK
        val missing = (listOf(CHANNEL_MASKS_FIELD) + COUNT_AND_MASK_FIELDS).filterNot { it in declared }
        throw NoSuchFieldException(
            "${cls.name} has no known channel fields: looked for $CHANNEL_MASKS_FIELD (Android 17) or " +
                "${COUNT_AND_MASK_FIELDS.joinToString(" + ")} (Android 11-16); missing: ${missing.joinToString(", ")}"
        )
    }

    /** Android 17's `new AudioFormat.ChannelMasks(positionMask, indexMask = 0, acnMask = 0)`, as its constructor builds it. */
    private fun channelMasks(positionMask: Int): Any {
        val i = Int::class.javaPrimitiveType
        return Class.forName("android.media.AudioFormat\$ChannelMasks").getDeclaredConstructor(i, i, i)
            .apply { isAccessible = true }.newInstance(positionMask, 0, 0)
    }

    private fun field(name: String) =
        AudioRecord::class.java.getDeclaredField(name).apply { isAccessible = true }

    /** @param channels the `ChannelMasks` object on Android 17, the IN position mask (an Int) before it. */
    private fun nativeSetup(
        record: AudioRecord, attributes: AudioAttributes, rates: IntArray, channels: Any,
        format: Int, bufferBytes: Int, session: IntArray,
    ): Int {
        val cls = AudioRecord::class.java
        val i = Int::class.javaPrimitiveType
        val l = Long::class.javaPrimitiveType
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // Android 11: (this, attributes, rate[], mask, indexMask, format, bytes, session[], opPackageName, nativePtr)
            val opPackage = runCatching {
                Class.forName("android.app.ActivityThread").getMethod("currentOpPackageName").invoke(null) as String?
            }.getOrNull()
            return cls.getDeclaredMethod("native_setup", Any::class.java, Any::class.java, IntArray::class.java, i, i, i, i,
                IntArray::class.java, String::class.java, l).apply { isAccessible = true }
                .invoke(record, WeakReference(record), attributes, rates, channels as Int, 0, format, bufferBytes, session, opPackage, 0L) as Int
        }
        return withAttributionParcel { parcel ->
            if (channels !is Int) {
                // Android 17: (weakThis, attributes, rate[], Object channelMasks, format, bytes, session[],
                //              Parcel attributionSource, nativePtr, maxSharedAudioHistoryMs, halInputFlags)
                cls.getDeclaredMethod("native_setup", Any::class.java, Any::class.java, IntArray::class.java, Any::class.java, i, i,
                    IntArray::class.java, Parcel::class.java, l, i, i).apply { isAccessible = true }
                    .invoke(record, WeakReference(record), attributes, rates, channels, format, bufferBytes, session, parcel, 0L, 0, 0) as Int
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 12–13: (…, session[], Parcel attributionSource, nativePtr, maxSharedAudioHistoryMs)
                cls.getDeclaredMethod("native_setup", Any::class.java, Any::class.java, IntArray::class.java, i, i, i, i,
                    IntArray::class.java, Parcel::class.java, l, i).apply { isAccessible = true }
                    .invoke(record, WeakReference(record), attributes, rates, channels, 0, format, bufferBytes, session, parcel, 0L, 0) as Int
            } else {
                // Android 14-16 added halInputFlags.
                cls.getDeclaredMethod("native_setup", Any::class.java, Any::class.java, IntArray::class.java, i, i, i, i,
                    IntArray::class.java, Parcel::class.java, l, i, i).apply { isAccessible = true }
                    .invoke(record, WeakReference(record), attributes, rates, channels, 0, format, bufferBytes, session, parcel, 0L, 0, 0) as Int
            }
        }
    }

    /**
     * Runs [setup] with the attribution the public constructor itself passes when it has no Context (Android 12+):
     * `AttributionSource.myAttributionSource()`, renamed `uid:<uid>` when it has no package (Android 13 and later).
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun withAttributionParcel(setup: (Parcel) -> Int): Int {
        var source = AttributionSource.myAttributionSource()
        if (source.packageName == null) {
            source = runCatching {
                AttributionSource::class.java.getMethod("withPackageName", String::class.java)
                    .invoke(source, "uid:" + Binder.getCallingUid()) as AttributionSource
            }.getOrDefault(source)
        }
        val state = AttributionSource::class.java.getDeclaredMethod("asScopedParcelState").apply { isAccessible = true }
            .invoke(source) as AutoCloseable
        return state.use {
            val parcel = it.javaClass.getDeclaredMethod("getParcel").apply { isAccessible = true }.invoke(it) as Parcel
            setup(parcel)
        }
    }
}
