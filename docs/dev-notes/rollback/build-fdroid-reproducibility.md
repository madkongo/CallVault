# Rollback: `build/fdroid-reproducibility`

🧪 VERIFYING (2026-10-31) — built and unit-tested; not installed on a phone. To settle: install-over on a
phone, then load a transcription model and a summary model (both are dlopen'd from these .so files), and
make one call (libaudiohandoff.so in the daemon).

## What changed
- `app/build.gradle.kts`: `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` for the CMake build (16 KB LOAD
  alignment on every .so we build), and `packaging.jniLibs.keepDebugSymbols += "**/*.so"` (Gradle no longer
  strips .so files at packaging time — F-Droid's reproducibility advice).
- `app/src/main/cpp/CMakeLists.txt`: release libraries are stripped at link time (`-Wl,--strip-all`), linked
  with `--build-id=none`, and compiled with `-ffile-prefix-map=<repo>=.` (no absolute build paths).
- Docs: backlog entry and this file. No Kotlin code, no versionCode/Name change, signing unchanged.

## Revert
```
git revert -m 1 c0895aec   # c0895aec = the merge of build/fdroid-reproducibility into integration/next
```
Then a clean native rebuild (`rm -rf app/.cxx app/build` before `assembleRelease`) so stale CMake caches do
not keep the old flags.

## What changes after rollback
- .so files go back to 4 KB alignment: they will not load on a phone running 16 KB pages.
- Gradle strips again at packaging; build-ids and absolute paths return. APK size is about the same either way.

## Symptoms that would justify rollback
- A model fails to load ("could not load backend", UnsatisfiedLinkError, dlsym failure) on a phone where the
  previous release worked — would point at the link-time strip removing something needed.
- The recorder daemon cannot load libaudiohandoff.so after install-over.
- The APK grows noticeably (it should not: measured numbers are in the backlog entry).
- A crash report becomes impossible to symbolicate and that matters more than reproducibility.

**OP9 check 2026-10-31 (2.4.8-next4, install-over):** libllamacv loaded and Gemma wrote a summary (90 s); libwhispercv loaded and large-v3-turbo transcribed a 15 s call (0.77× real time). Still 🧪 until the maintainer confirms; the APK hash reproducibility itself is not tested.
