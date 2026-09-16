/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

/**
 * Which audio files CallVault will take in, and what it stores each of them as.
 *
 * The only reason to import a file is to transcribe it, so the only files worth accepting are the
 * ones this phone can decode. That is not a matter of taste: [com.baba.callvault.transcription.AudioDecoder]
 * hands the container to `MediaCodec.createDecoderByType` and then requires 16-bit PCM back, so a
 * format with no system decoder — or one whose decoder emits float — fails at transcription time,
 * long after the file was copied in, catalogued and listed.
 *
 * **This list is half the gate, and the cheaper half.** It answers "is this a shape CallVault knows
 * how to store and name", which is all that can be known before any bytes have been read. The other
 * half is [AudioImport]'s decode probe, which asks the device itself whether it can actually read
 * *this* file — because the list cannot. Two files with the same extension are not equally
 * decodable: a 32-bit-float WAV and a 16-bit one are both `.wav`, and only one of them survives
 * [com.baba.callvault.transcription.AudioDecoder]'s 16-bit requirement.
 *
 * Accepting anything and discovering later produces the worst outcome available: a row in the user's
 * library that looks like every other row, plays (ExoPlayer and MediaCodec do not agree on what they
 * support), and silently cannot be transcribed. Turning the file down in the moment, with a reason,
 * is strictly better.
 *
 * The list was not guessed. Every entry is decoded on a device by
 * `com.baba.callvault.transcription.AudioImportFormatProbe`, which fails the build if reality and
 * this object part company.
 *
 * Nothing here grants a permission. The picker is SAF (`OpenDocument`), which returns a URI for the
 * one file the user chose; `READ_MEDIA_AUDIO` would let a call recorder read every audio file on the
 * phone, which is indefensible and would be flagged on F-Droid besides.
 */
object ImportableAudio {

    /**
     * Extension to the MIME type the copy is created with, for every format we take.
     *
     * The MIME matters twice over: SAF uses it to create the destination document, and
     * [RecordingsRepository.enumerateFolder] falls back to the provider's reported type when it does
     * not recognise an extension. Writing the wrong one would make a file that imports fine and then
     * disappears from the list on the next catalog re-seed.
     */
    private val ACCEPTED = mapOf(
        // Opus, in an Ogg container. Both spellings are the same bytes: WhatsApp names its voice
        // notes ".opus" and CallVault names its own recordings ".ogg".
        "opus" to "audio/ogg",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        // AAC in MP4 — what a phone's own voice recorder and most messaging apps write.
        "m4a" to "audio/mp4",
        "mp4" to "audio/mp4",
        "aac" to "audio/aac",
        "mp3" to "audio/mpeg",
        // WAV, which the plan expected to fail and which measured otherwise: the emulator's
        // `c2.android.raw.decoder` reads a 16-bit file exactly as well as any other format, and the
        // CDD requires PCM/WAVE decoding of every handheld. What it does NOT guarantee is the sample
        // format — a 32-bit-float WAV makes the decoder emit float, which AudioDecoder refuses by
        // design rather than misreading as shorts. That is a property of the file, not of the
        // extension, so it is AudioImport's decode probe that turns such a file down, not this list.
        "wav" to "audio/wav",
    )

    /**
     * MIME type to the extension a source carrying that type but no usable name is stored under.
     *
     * The spellings are wider than [ACCEPTED]'s values because a provider chooses its own: the same
     * WAV file is `audio/wav` from one and `audio/x-wav` or `audio/vnd.wave` from another, and an
     * MP3 is `audio/mpeg` or `audio/mp3`. Matching only the type we happen to *write* would refuse a
     * perfectly good file for spelling its type differently.
     */
    private val MIME_TO_EXTENSION = mapOf(
        "audio/ogg" to "ogg",
        "audio/opus" to "opus",
        "application/ogg" to "ogg",
        "audio/mp4" to "m4a",
        "audio/m4a" to "m4a",
        "audio/x-m4a" to "m4a",
        "audio/aac" to "aac",
        "audio/mpeg" to "mp3",
        "audio/mp3" to "mp3",
        "audio/wav" to "wav",
        "audio/x-wav" to "wav",
        "audio/wave" to "wav",
        "audio/vnd.wave" to "wav",
    )

    /** Every extension an import may be stored under, for the folder scan to recognise. */
    val ACCEPTED_EXTENSIONS: Set<String> = ACCEPTED.keys

    /** The extension of [displayName], lower-cased and without its dot, or null when it has none. */
    fun extensionOf(displayName: String?): String? {
        val name = displayName?.trim().orEmpty()
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        return name.substring(dot + 1).lowercase()
    }

    /** Whether a file called [displayName] is one we can decode, judged on its extension alone. */
    fun isAcceptedName(displayName: String?): Boolean = extensionOf(displayName) in ACCEPTED

    /**
     * What to store a source called [displayName], reported as [mimeType], under — or null to refuse.
     *
     * The extension is believed first and the MIME only consulted when there is none, because a
     * provider's type is frequently `application/octet-stream` for a file whose name says plainly
     * what it is. Where neither answers, the file is refused rather than guessed at: storing audio
     * under an extension nothing can open is the failure this object exists to prevent.
     *
     * @return the extension and the MIME type to create the copy with, or null when unusable.
     */
    fun storedAs(displayName: String?, mimeType: String?): StoredAs? {
        val extension = extensionOf(displayName)
        ACCEPTED[extension]?.let { return StoredAs(requireNotNull(extension), it) }

        val mime = mimeType?.trim()?.lowercase()?.substringBefore(';')?.trim()
        val byMime = MIME_TO_EXTENSION[mime] ?: return null
        // Stored under OUR spelling of the type, not the provider's: the name and the type on the
        // copy have to agree with what the folder scan looks for, and a document created as
        // "audio/vnd.wave" would come back from a re-seed as a type nothing here recognises.
        return StoredAs(byMime, requireNotNull(ACCEPTED[byMime]))
    }

    /** Where an accepted import lands: the extension it is stored under and its MIME type. */
    data class StoredAs(val extension: String, val mimeType: String)
}
