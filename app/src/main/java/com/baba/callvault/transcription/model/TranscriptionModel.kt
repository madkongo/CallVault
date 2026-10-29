/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.model

/**
 * The whisper.cpp models CallVault offers, and nothing else.
 *
 * The spec settled on two tiers after measuring on the OP12: `tiny` and `base` are unusable for
 * Hebrew, and `medium` is dominated by turbo on both quality and speed, so offering them would only
 * invite a bad choice. A third arrived later — see [LARGE_V3_TURBO_Q8_0] — and nothing outside this
 * file may assume how many there are: the catalogue is enumerated with `entries` everywhere,
 * including in the wizard's size note, which used to name two of them by hand.
 *
 * **Every general tier here must be multilingual.** All the languages the app offers have to keep a
 * working option, so a single-language build (`*.en`, or a fine-tune) can never replace a tier, only
 * ever be an addition beside one. Both `large-v3-turbo` quants and `small` are multilingual, which is
 * what makes the choice between them purely one of size, speed and fidelity. A single-language
 * addition declares its [language], and `TranscriptionModelOffer` lists it only when that language is
 * pinned (see [LARGE_V3_TURBO_PERSIAN_Q5_0]).
 *
 * Digests and sizes are the published Git-LFS values from the model repository, and the SMALL_Q5_1
 * digest was additionally checked against the exact file that was verified on-device. They are
 * load-bearing: ggml's failure mode on a corrupt model is a crash in native code, so a model is only
 * ever used after its digest matches.
 *
 * @param id            Stable key stored on a transcript, so a re-run with a better model is
 *                      distinguishable from a re-run of the same one.
 * @param fileName      Name on disk and in the download URL.
 * @param url           Direct download URL.
 * @param sha256        Lower-case hex SHA-256 of the complete file.
 * @param sizeBytes     Exact published length, used as the cheap completeness check.
 * @param realTimeFactor Seconds of processing per second of audio — the opening estimate only, which
 *                      `AppPreferences.getTranscriptionRtf` supersedes per model id once this device
 *                      has actually run one. Used to warn honestly about how long a long call takes.
 * @param language      Null for a general multilingual tier. Otherwise the whisper language code the
 *                      tier was trained for; the picker offers it only while that language is pinned.
 *
 * The entries are ordered by **download size**, which is what the picker and the wizard's size note
 * both show, and deliberately not by how good they are — the largest is now also the fastest, so any
 * "better goes last" ordering would be describing an axis that no longer runs the same way.
 */
enum class TranscriptionModel(
    override val id: String,
    override val fileName: String,
    override val url: String,
    override val sha256: String,
    override val sizeBytes: Long,
    val realTimeFactor: Double,
    val language: String? = null
) : DownloadableModel {
    /** Fast tier. Hebrew comes out as gist rather than clean prose, at roughly real time. */
    SMALL_Q5_1(
        id = "small-q5_1",
        fileName = "ggml-small-q5_1.bin",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
        sha256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
        sizeBytes = 190_085_487L,
        realTimeFactor = 0.99
    ),

    /**
     * Same model as [LARGE_V3_TURBO_Q8_0], quantised smaller. Kept **only** for its download size.
     *
     * It is no longer the tier to recommend: q8_0 is both faster and no less accurate, so the 300 MB
     * saved here is bought with roughly twice the transcription time.
     *
     * **It must not be removed from this enum.** `ModelRepository` can only name a file it can reach
     * through an entry here, and the Settings delete row only ever offers the *selected* tier, so
     * the single way a user reclaims these 574 MB is to select this tier and delete it. Drop the
     * entry and that path disappears with it, stranding the file on the phone of everyone who
     * already downloaded it — invisible to the app, and in the app's private files directory where
     * a file manager cannot reach it either.
     */
    LARGE_V3_TURBO_Q5_0(
        id = "large-v3-turbo-q5_0",
        fileName = "ggml-large-v3-turbo-q5_0.bin",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
        sha256 = "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
        sizeBytes = 574_041_195L,
        realTimeFactor = 2.16
    ),

    /**
     * Persian-tuned tier (GitHub #55): large-v3-turbo fine-tuned on Persian speech by givia —
     * https://huggingface.co/givia/whisper-large-v3-turbo-persian, **CC BY 4.0**, which is why the
     * picker shows its credit (`transcription_model_persian_licence`) and NOTICE.md names it.
     *
     * The file is a plain `whisper-quantize … q5_0` of givia's own ggml export (revision 9626d385,
     * SHA-256 743c4030…), re-uploaded by pejmans21, whose model card credits givia and states CC BY 4.0.
     * The byte-identical file (same size, same digest) is also at
     * hasanghaffari93/whisper-large-v3-turbo-persian-ggml-q5_0 as `ggml-persian-v3-turbo-q5_0.bin`.
     * Unlike the other tiers the URL is **pinned to a commit**: a third-party repo can be rewritten,
     * and the digest would then fail every download with nothing on screen pointing at why.
     *
     * Same architecture and quant as [LARGE_V3_TURBO_Q5_0], so the same opening [realTimeFactor] — and
     * the same size to the byte. Import can therefore not tell the two apart by size; the digest does,
     * so picking the wrong one of them fails as the wrong file rather than installing it.
     *
     * Offered only while the transcription language is pinned to Persian. Selectable, **not quality-
     * tested on calls** — the maintainer's call. 🧪 One external tester measured it about 3x slower
     * with temperature fallback on, which this app keeps on (see whispercv.cpp).
     */
    LARGE_V3_TURBO_PERSIAN_Q5_0(
        id = "large-v3-turbo-persian-q5_0",
        fileName = "ggml-large-v3-turbo-persian-q5_0.bin",
        url = "https://huggingface.co/pejmans21/whisper-large-v3-turbo-persian-ggml/resolve/" +
            "28739ad1094c007d67fc7e2276b1e9b709d58e2d/ggml-large-v3-turbo-persian-q5_0.bin",
        sha256 = "d832fe5cd81c756bc1962765cff4a33cacde00a7701809837875245870b6d5a8",
        sizeBytes = 574_041_195L,
        realTimeFactor = 2.16,
        language = "fa"
    ),

    /**
     * Best tier. The same large-v3-turbo, quantised to 8 bits — **faster than [LARGE_V3_TURBO_Q5_0]
     * despite being 300 MB larger**, which is the opposite of what the sizes suggest.
     *
     * The reason is in ggml, not in the model. Its ARM CPU backend keeps two accelerated paths for
     * matrix multiplication — the repacked GEMM traits in `ggml-cpu/repack.cpp`, and the i8mm
     * `vmmlaq_s32` kernels in `arch/arm/quants.c` — and **Q5_0 and Q5_1 are in neither**. Q8_0 is in
     * both: `repack.cpp` returns `q8_0_4x4_q8_0` on any core with DOTPROD and `q8_0_4x8_q8_0` on one
     * with i8mm. So the two tiers this app shipped were, by coincidence, exactly the two quant types
     * that get the generic scalar path. Enabling the ARM feature variants in the build (2ce45c8)
     * bought summarisation 1.74x and transcription only 1.17x for precisely that reason.
     *
     * Measured 2026-08-26 on the OP9 Pro (SD888 — DOTPROD yes, i8mm **no**, so this is the slower of
     * the two Q8_0 kernels), 6 threads, one real Hebrew call of 295.3 s, same decoded audio:
     *
     * | tier        | whisper | rtf   | peak PSS |
     * |-------------|---------|-------|----------|
     * | small q5_1  | 111.4 s | 0.377 |  716 MB  |
     * | turbo q8_0  | 213.1 s | 0.722 | 1258 MB  |
     * | turbo q5_0  | 421.8 s | 1.429 | 1098 MB  |
     *
     * Read the last two rows twice: the **larger** download is the **faster** tier, by 1.98x. The
     * order was confirmed by running each tier both first-after-idle and immediately after the
     * other, so it is not thermal drift — and q5_0 is the one that suffers from heat, losing 12%
     * (421.8 s to 473.7 s) when it runs second, while q8_0 loses 0.6% (213.1 s to 214.4 s). Doing
     * less work, it throttles less.
     *
     * Accuracy, scored against a known Hebrew paragraph rather than by eye: q8_0 is **never worse**.
     * On clean speech it was better — 7.8% WER against 13.7%, the difference being that q5_0 turned
     * "אם עברת דירה" into the meaningless "עם עבר תדירה" and q8_0 got it right. On the same audio
     * degraded to phone-call band with noise, the two produced character-identical transcripts, as
     * they did on two real calls. So the honest summary is: no regression, an occasional real win,
     * and speed is the reason to prefer it.
     *
     * The costs are real and are the reason this is not simply a free upgrade: 874 MB to download
     * instead of 574 MB, about 240 MB more resident while running, and a model load about 1.5 s
     * slower (one-off per run, against minutes saved). A phone without DOTPROD would get neither
     * path and would pay the size for nothing — but DOTPROD is ARMv8.2, which every device this app
     * supports (minSdk 30, arm64) has.
     *
     * [realTimeFactor] is a **first estimate only** — `AppPreferences.getTranscriptionRtf` replaces
     * it with this device's own measurement after a run, per model id. It is the OP12's 2.16 for
     * q5_0 scaled by the 0.505 ratio measured here, and is deliberately an upper bound: the OP12 has
     * i8mm and so takes the *faster* Q8_0 kernel. Treat it as provisional — the OP12's own two
     * figures do not scale to this device by any single constant, and they predate 2ce45c8. Measure
     * on the OP12 when it is next free.
     */
    LARGE_V3_TURBO_Q8_0(
        id = "large-v3-turbo-q8_0",
        fileName = "ggml-large-v3-turbo-q8_0.bin",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q8_0.bin",
        sha256 = "317eb69c11673c9de1e1f0d459b253999804ec71ac4c23c17ecf5fbe24e259a1",
        sizeBytes = 874_188_075L,
        realTimeFactor = 1.10
    );

    companion object {
        /**
         * The tier chosen when the user has expressed no preference.
         *
         * Best rather than Fast, decided 2026-08-17. Fast produces Hebrew *gist*; Best produces clean
         * Hebrew, and a transcript you cannot trust is not worth the battery it cost. The price is a
         * 574 MB download and roughly 2.2x real time instead of 1x, which the user accepted — a
         * 30-minute call takes about 65 minutes rather than 30.
         *
         * **q8_0, because it measured nearly twice as fast at no cost in accuracy.**
         *
         * This constant is not only the new-install default: `AppPreferences.getTranscriptionModelId`
         * falls back to it for anyone who never *changed* the setting, and accepting the wizard's
         * suggestion does not count as changing it. Moving it therefore re-points every such install
         * at a model it may not have downloaded — which would normally demand a migration writing the
         * installed tier into the preference first.
         *
         * That migration is not needed here, and the reason is a fact with an expiry date:
         * **transcription has never been released.** It arrived in 2.0.0, and no 2.x tag exists — so
         * outside this repository nobody has a q5_0 file on disk or a stored preference to protect.
         *
         * Once 2.x ships, that argument is spent. Any future move of this constant is a breaking
         * change for people who already downloaded a model, and needs the migration.
         */
        val DEFAULT = LARGE_V3_TURBO_Q8_0

        fun fromId(id: String?): TranscriptionModel? = entries.firstOrNull { it.id == id }

        /**
         * 📐 CALCULATED, not measured: a conservative sequential read rate for phone flash storage,
         * used only to seed [seedLoadMs] before a device has timed a real run.
         *
         * Deliberately pessimistic. Over-promising is the failure that gets reported — a dialog that
         * says thirty seconds and takes two minutes reads as a hang — while a first estimate that is
         * slightly long is replaced by a measured one as soon as the run finishes.
         */
        private const val SEED_LOAD_BYTES_PER_MS = 200_000L
    }

    /**
     * Opening guess at what loading this model costs, before the phone has timed it once.
     *
     * Derived from [sizeBytes] rather than written down per tier, because it is dominated by reading
     * the file off flash: an 874 MB model cannot load as fast as a 190 MB one on the same phone, and
     * a table of hand-entered numbers would drift the moment a tier is added. Replaced by
     * `AppPreferences.getTranscriptionLoadMs` after the first real run.
     */
    val seedLoadMs: Long get() = sizeBytes / SEED_LOAD_BYTES_PER_MS
}
