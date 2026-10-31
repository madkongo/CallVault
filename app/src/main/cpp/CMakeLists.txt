# Native half of "Resilient recording" (audio-capture handoff): extracts the IAudioRecord binder +
# cblk ashmem fd in the daemon, and drains the cblk ring in the app.
cmake_minimum_required(VERSION 3.22.1)
project(audiohandoff)

# --- Reproducible release libraries (F-Droid) ---
#
# Placed first so every target below, including the whisper.cpp / llama.cpp submodules, inherits it.
#  - Strip at link time in non-Debug builds. app/build.gradle.kts turns Gradle's packaging-time strip
#    off (keepDebugSymbols), because F-Droid reports that step as non-reproducible. AGP builds release
#    as RelWithDebInfo, so without this the APK would carry full DWARF with absolute build paths.
#    --strip-all only removes .symtab/.debug_*; .dynsym (JNI entry points, the ggml_backend_init that
#    GGML_BACKEND_DL dlsym's) is untouched. Debug builds keep their symbols for ndk-stack/lldb.
#  - No build-id note and no absolute source paths (ggml's __FILE__ in abort messages), per
#    https://f-droid.org/docs/Reproducible_Builds/.
add_link_options($<$<NOT:$<CONFIG:Debug>>:-Wl,--strip-all>)
add_link_options("LINKER:--build-id=none")
get_filename_component(CALLVAULT_REPO_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../.. ABSOLUTE)
add_compile_options("-ffile-prefix-map=${CALLVAULT_REPO_ROOT}=.")

add_library(audiohandoff SHARED audiohandoff.cpp)

find_library(log-lib log)

target_link_libraries(audiohandoff ${log-lib})

# --- On-device transcription (whisper.cpp) ---
#
# Built from the tag-pinned third_party/whisper.cpp submodule. Nothing here is forked: only our own
# small JNI (whispercv.cpp) is ours. arm64-v8a only, matching the app's existing abiFilters.
#
# GGML_OPENMP must stay OFF. Not for the reason this comment used to give -- NDK 27.2 does ship a
# libomp for aarch64 and the link succeeds. The problem is what it links against: ggml picks up the
# SHARED libomp.so, which is part of the NDK and not part of the APK, so the app installs fine and
# then dies with UnsatisfiedLinkError the first time a model is loaded on a real phone.
set(WHISPER_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

# Optimise the inference code even in a debug build.
#
# AGP compiles debug variants with CMAKE_BUILD_TYPE=Debug, and the NDK's Debug flags are -O0. For
# ggml that is not a mild slowdown: it is roughly 13x. Measured on the OP12, a 47 s clip took
# 10m26s at -O0 against ~47 s for the same model in an -O3 build, which makes an on-device debug
# run useless for timing and painful for anything else.
#
# This is placed before add_subdirectory so whisper, ggml and our own whispercv all inherit it;
# audiohandoff is declared above and deliberately keeps the default -O0 so it stays debuggable.
add_compile_options($<$<CONFIG:Debug>:-O3>)

set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)

# Build ggml's CPU kernels for the ARM extensions this decade's phones actually have.
#
# DO NOT "SIMPLIFY" THESE TWO LINES AWAY. Without them ggml is compiled with no -march at all, and
# it is the absence of a flag rather than the presence of a wrong one, so nothing warns. Every
# dotprod / i8mm / fp16 kernel is #ifdef'd out: `ggml_vdotq_s32` degrades to a six-instruction
# emulation of the single SDOT the phone has had since 2018, and GGML_CPU_REPACK -- on by default --
# is inert, because its dispatch asks `ggml_cpu_has_matmul_int8()`, itself a compile-time #ifdef
# returning 0. Measured on the OP12 at the app's own 6 threads, off -> on:
#
#   summarisation prefill    97.5 -> 309.7 t/s
#   summarisation generation 69.2 ->  84.1 t/s
#   transcription encode     4848 -> 4073 ms
#
# The multi-variant route rather than a bare -DGGML_CPU_ARM_ARCH=armv8.6-a+i8mm: an arch flag emits
# SDOT unconditionally, so a phone without dotprod -- which minSdk 30 still admits -- would SIGILL
# on the first matmul. GGML_CPU_ALL_VARIANTS instead builds the seven Android feature sets ggml
# defines (android_armv8.0_1 .. android_armv9.2_2) as separate .so files and picks the best one the
# CPU can actually run, from HWCAP, at load time. That is what GGML_BACKEND_DL buys: it is a
# prerequisite ggml enforces with a FATAL_ERROR, not an independent choice.
#
# Two things this depends on, both of which will look like "the model just won't load" if broken:
#  - the backends must be dlopen'd from the app's nativeLibraryDir. ggml's own default search path
#    is the executable's directory (/system/bin) and the cwd (/), neither of which is ours, so
#    whispercv.cpp and llamacv.cpp call ggml_backend_load_all_from_path with the real one.
#  - jniLibs { useLegacyPackaging = true } in app/build.gradle.kts, which extracts the libraries to
#    that directory as real files. Left compressed inside the APK there is nothing to enumerate.
# One side effect worth knowing before it is discovered as an OOM. GGML_CPU_REPACK was already ON
# (it is ggml's default) but did nothing; it now works, and it works by keeping a second, repacked
# copy of the quantised weights. Measured on the OP9 Pro with a 484 MB model, summarising the two
# benchmark samples: 185 s / 800 MB PSS before, 155 s / 790 MB with variants but REPACK off,
# 105 s / 1040 MB with both. So repack is most of the speed AND all of the memory -- about +50% of
# the model's own size. If a phone starts refusing to load the summariser, add
# `set(GGML_CPU_REPACK OFF CACHE BOOL "" FORCE)` here: it costs ~1.5x on summarisation and gives the
# memory straight back.
set(GGML_BACKEND_DL        ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS  ON  CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/whisper.cpp)

add_library(whispercv SHARED whispercv.cpp)
target_link_libraries(whispercv whisper ${log-lib})

# --- On-device summarisation (llama.cpp) ---
#
# Same org and the same ggml as whisper.cpp above. Both projects vendor ggml and both guard it with
# `if (NOT TARGET ggml)`, so whichever is added first creates the target and the second reuses it --
# no duplicate-target failure, and no second copy of ggml in the APK. Order therefore decides WHOSE
# ggml both engines compile against, which is the thing to check when either fails to build.
#
# GGML_OPENMP, the CPU-variant settings and the Debug -O3 override above apply to these targets too,
# and matter just as much: summarisation is the same ggml kernels doing the same arithmetic. The
# variants are in fact worth more here than to whisper -- a prefill is one large int8 matmul after
# another, which is precisely what i8mm exists for.
set(LLAMA_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp)

set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

add_library(llamacv SHARED llamacv.cpp)
target_link_libraries(llamacv llama ${log-lib})
