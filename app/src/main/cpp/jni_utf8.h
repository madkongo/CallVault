/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

// Shared by whispercv.cpp and llamacv.cpp: model output must pass through here before NewStringUTF.
// A summary cut mid-character (Hebrew, most often) used to be handed to NewStringUTF raw, which
// aborts the whole process (C47).

#pragma once

#include <cstdint>
#include <string>

/**
 * Whisper's text, made safe to hand to JNI.
 *
 * `NewStringUTF` does not validate and does not fail politely: given a byte sequence that is not
 * well-formed **modified** UTF-8 it aborts the entire process with SIGABRT. That is not theoretical
 * — it happened on a real 26-minute Hebrew call, where whisper emitted a segment cut in the middle of
 * a two-byte character (`0xd7` followed by another `0xd7`) and the app died outright:
 *
 *     JNI DETECTED ERROR IN APPLICATION: input is not valid Modified UTF-8:
 *     illegal continuation byte 0xd7 ... in call to NewStringUTF
 *
 * whisper builds a segment by concatenating tokens, and a token boundary is not a character
 * boundary, so a truncated multi-byte character is an ordinary thing for it to produce — most often
 * on non-Latin scripts, where nearly every character is multi-byte. A transcriber that crashes the
 * app on some Hebrew calls and not others is the worst possible shape for this bug.
 *
 * Two departures from plain UTF-8, both required by the *modified* form JNI expects:
 *  - a four-byte sequence (anything outside the BMP, e.g. an emoji) must be re-encoded as a
 *    surrogate pair of two three-byte sequences, not passed through;
 *  - an embedded NUL would have to become `0xC0 0x80`, which cannot arise here because the input is
 *    already a NUL-terminated C string.
 *
 * Malformed bytes are dropped rather than replaced. A replacement character in the middle of a word
 * is not more useful to a reader than the word simply being a character shorter, and it would travel
 * into the summary and the search index as content.
 */
static inline std::string to_modified_utf8(const char *s) {
    std::string out;
    if (s == nullptr) return out;

    const auto *p = reinterpret_cast<const unsigned char *>(s);
    while (*p) {
        const unsigned char c = *p;

        int len;
        if (c < 0x80)             len = 1;
        else if ((c & 0xE0) == 0xC0) len = 2;
        else if ((c & 0xF0) == 0xE0) len = 3;
        else if ((c & 0xF8) == 0xF0) len = 4;
        else { ++p; continue; }              // stray continuation byte, or an invalid lead

        // Every continuation byte must be present and well-formed. This is the check that was
        // missing: a sequence truncated by the end of the string fails here instead of aborting.
        bool ok = true;
        for (int k = 1; k < len; ++k) {
            if (p[k] == 0 || (p[k] & 0xC0) != 0x80) { ok = false; break; }
        }
        if (!ok) { ++p; continue; }

        if (len < 4) {
            out.append(reinterpret_cast<const char *>(p), len);
            p += len;
            continue;
        }

        // Outside the BMP: decode, then emit the surrogate pair as two three-byte sequences, which
        // is what modified UTF-8 requires and what plain UTF-8 would get wrong.
        const uint32_t cp = ((uint32_t) (p[0] & 0x07) << 18) | ((uint32_t) (p[1] & 0x3F) << 12) |
                            ((uint32_t) (p[2] & 0x3F) << 6)  |  (uint32_t) (p[3] & 0x3F);
        p += 4;
        if (cp < 0x10000 || cp > 0x10FFFF) continue;   // overlong or out of range
        const uint32_t v    = cp - 0x10000;
        const uint32_t hi   = 0xD800 + (v >> 10);
        const uint32_t lo   = 0xDC00 + (v & 0x3FF);
        for (uint32_t half : {hi, lo}) {
            out.push_back((char) (0xE0 | (half >> 12)));
            out.push_back((char) (0x80 | ((half >> 6) & 0x3F)));
            out.push_back((char) (0x80 | (half & 0x3F)));
        }
    }
    return out;
}
