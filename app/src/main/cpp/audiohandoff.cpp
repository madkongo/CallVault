// Native half of "Resilient recording" (audio-capture handoff).
//
// The privileged shell-uid daemon creates an AudioRecord, and this code extracts its IAudioRecord
// binder + control-block (cblk) ashmem fd so they can be handed to the always-alive app. The app then
// drains the cblk ring itself, which is why a recording survives the daemon being killed mid-call.
//
// Everything here is deliberately libc/NDK-only: the app process runs under a restricted linker
// namespace that cannot load libaudioclient at all, so the few framework symbols we need
// (javaObjectForIBinder) are resolved manually out of already-mapped libs instead of via dlsym.

#include <jni.h>
#include <android/log.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>
#include <elf.h>
#include <cstring>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <csetjmp>
#include <csignal>
#include <cerrno>
#include <vector>

#define TAG "CV:AudioHandoff"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

// ---- Manual symbol resolution: reach a function in an already-loaded lib that our ISOLATED app
// classloader namespace can't dlsym (the daemon's .so can't dlopen libandroid_runtime/libbinder, but
// those libs ARE mapped in the process). We compute the runtime address = load_bias + st_value, where
// load_bias comes from /proc/self/maps and st_value from the on-disk ELF .dynsym. Bypasses nativeloader.

// Load bias of a mapped lib = lowest mapping start for that path MINUS the min PT_LOAD p_vaddr.
static uintptr_t manualLibBase(const char *nameSuffix) {
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return 0;
    char line[512];
    uintptr_t base = 0;
    size_t sl = strlen(nameSuffix);
    while (fgets(line, sizeof(line), f)) {
        char *path = strchr(line, '/');
        if (!path) continue;
        size_t pl = strlen(path);
        if (pl && path[pl - 1] == '\n') { path[--pl] = 0; }
        if (pl >= sl && strcmp(path + pl - sl, nameSuffix) == 0) {
            uintptr_t start = strtoull(line, nullptr, 16);
            if (base == 0 || start < base) base = start;   // lowest mapping = load address of hdr
        }
    }
    fclose(f);
    return base;
}

// Symbol's link-time st_value from the ELF file's .dynsym, plus the min PT_LOAD vaddr (for load_bias).
static uintptr_t manualSymOffset(const char *fullPath, const char *symbol, uintptr_t *minVaddrOut) {
    int fd = open(fullPath, O_RDONLY);
    if (fd < 0) return 0;
    struct stat st{};
    if (fstat(fd, &st) != 0) { close(fd); return 0; }
    void *m = mmap(nullptr, st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (m == MAP_FAILED) return 0;
    auto *b = static_cast<uint8_t *>(m);
    auto *eh = reinterpret_cast<Elf64_Ehdr *>(b);
    uintptr_t off = 0, minVaddr = UINTPTR_MAX;
    auto *ph = reinterpret_cast<Elf64_Phdr *>(b + eh->e_phoff);
    for (int i = 0; i < eh->e_phnum; i++)
        if (ph[i].p_type == PT_LOAD && ph[i].p_vaddr < minVaddr) minVaddr = ph[i].p_vaddr;
    auto *sh = reinterpret_cast<Elf64_Shdr *>(b + eh->e_shoff);
    for (int i = 0; i < eh->e_shnum; i++) {
        if (sh[i].sh_type == SHT_DYNSYM) {
            auto *syms = reinterpret_cast<Elf64_Sym *>(b + sh[i].sh_offset);
            size_t n = sh[i].sh_size / sizeof(Elf64_Sym);
            const char *strtab = reinterpret_cast<const char *>(b + sh[sh[i].sh_link].sh_offset);
            for (size_t k = 0; k < n; k++) {
                if (strcmp(strtab + syms[k].st_name, symbol) == 0) { off = syms[k].st_value; break; }
            }
        }
    }
    munmap(m, st.st_size);
    if (minVaddrOut) *minVaddrOut = (minVaddr == UINTPTR_MAX) ? 0 : minVaddr;
    return off;
}

// Runtime address of `symbol` in an already-loaded lib. `suffix` matches /proc/self/maps; `fullPath`
// is the on-disk ELF (e.g. /system/lib64/libandroid_runtime.so).
static void *manualResolve(const char *suffix, const char *fullPath, const char *symbol) {
    uintptr_t base = manualLibBase(suffix);
    uintptr_t minVaddr = 0;
    uintptr_t off = manualSymOffset(fullPath, symbol, &minVaddr);
    if (!base || !off) { LOGI("manualResolve: %s base=%p off=%p FAIL", symbol, (void*)base, (void*)off); return nullptr; }
    void *addr = reinterpret_cast<void *>(base + off - minVaddr);
    LOGI("manualResolve: %s base=0x%lx off=0x%lx minVaddr=0x%lx -> %p",
         symbol, (unsigned long)base, (unsigned long)off, (unsigned long)minVaddr, addr);
    return addr;
}

// Guarded pointer reads (scanning object memory for candidate pointers can't crash).
static sigjmp_buf g_hf;
static void hfHandler(int) { siglongjmp(g_hf, 1); }
static bool hfPlaus(void *p) { uintptr_t v = (uintptr_t) p; return (v & 7) == 0 && (v & 0x00FFFFFFFFFFFFFFull) >= 0x10000; }
static bool hfRead(void *a, void **o) { if (!hfPlaus(a)) return false; if (sigsetjmp(g_hf, 1)) return false; *o = *reinterpret_cast<void **>(a); return true; }

// javaObjectForIBinder(JNIEnv*, const sp<IBinder>&) -> jobject (Java BinderProxy), manually resolved.
typedef jobject (*JavaObjForIBinderFn)(JNIEnv *, const void *);
static JavaObjForIBinderFn g_javaObjForIBinder = nullptr;

// Validate that `ptr` is a native android::AudioRecord: ptr+0x190 -> BpAudioRecord, +0x10 -> a BpBinder
// (plausible object with a plausible vptr). Used to pick the right long field from the Java AudioRecord.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_baba_callvault_services_recording_handoff_AudioHandoffNative_nativeValidateArPtr(JNIEnv *, jclass, jlong ptr) {
    struct sigaction sa{}, o1{}, o2{}; sa.sa_handler = hfHandler; sigemptyset(&sa.sa_mask);
    sigaction(SIGSEGV, &sa, &o1); sigaction(SIGBUS, &sa, &o2);
    void *ar = reinterpret_cast<void *>(ptr), *bpAR = nullptr, *bp = nullptr, *vptr = nullptr;
    bool ok = hfRead(reinterpret_cast<uint8_t *>(ar) + 0x190, &bpAR) && hfPlaus(bpAR) &&
              hfRead(reinterpret_cast<uint8_t *>(bpAR) + 0x10, &bp) && hfPlaus(bp) &&
              hfRead(bp, &vptr) && hfPlaus(vptr);
    sigaction(SIGSEGV, &o1, nullptr); sigaction(SIGBUS, &o2, nullptr);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Extract the IAudioRecord BpBinder (ptr+0x190 -> +0x10) and wrap it into a Java IBinder via the
// manually-resolved javaObjectForIBinder (bypassing the isolated-namespace dlsym block). Returns the
// Java IBinder (BinderProxy) the daemon can hand to the app via BinderDelivery, or null.
extern "C" JNIEXPORT jobject JNICALL
Java_com_baba_callvault_services_recording_handoff_AudioHandoffNative_nativeExtractBinder(JNIEnv *env, jclass, jlong ptr) {
    if (!g_javaObjForIBinder) {
        g_javaObjForIBinder = (JavaObjForIBinderFn) manualResolve(
            "libandroid_runtime.so", "/system/lib64/libandroid_runtime.so",
            "_ZN7android20javaObjectForIBinderEP7_JNIEnvRKNS_2spINS_7IBinderEEE");
    }
    if (!g_javaObjForIBinder) { LOGI("extractBinder: javaObjectForIBinder unresolved"); return nullptr; }

    struct sigaction sa{}, o1{}, o2{}; sa.sa_handler = hfHandler; sigemptyset(&sa.sa_mask);
    sigaction(SIGSEGV, &sa, &o1); sigaction(SIGBUS, &sa, &o2);
    void *ar = reinterpret_cast<void *>(ptr), *bpAR = nullptr, *bp = nullptr;
    bool ok = hfRead(reinterpret_cast<uint8_t *>(ar) + 0x190, &bpAR) && hfPlaus(bpAR) &&
              hfRead(reinterpret_cast<uint8_t *>(bpAR) + 0x10, &bp) && hfPlaus(bp);
    sigaction(SIGSEGV, &o1, nullptr); sigaction(SIGBUS, &o2, nullptr);
    if (!ok) { LOGI("extractBinder: couldn't read BpBinder at ar+0x190/+0x10"); return nullptr; }

    const void *spRef = bp;   // sp<IBinder> = { IBinder* }
    jobject jb = g_javaObjForIBinder(env, &spRef);
    LOGI("extractBinder: BpBinder=%p -> Java IBinder=%p", bp, jb);
    return jb;
}

// ioctl ASHMEM_GET_SIZE on an fd (the app uses this on the received cblk fd to mmap the RIGHT size,
// which varies with sample rate / frameCount — hardcoding 8192 broke at 48 kHz). -1 on failure.
extern "C" JNIEXPORT jint JNICALL
Java_com_baba_callvault_services_recording_handoff_AudioHandoffNative_nativeAshmemSize(JNIEnv *, jclass, jint fd) {
    return ioctl(fd, 0x00007704 /*ASHMEM_GET_SIZE*/, 0);
}

// Find the cblk ashmem fd in this process and return a DUP of it (caller owns the dup; the original stays
// owned by the AudioRecord). Identifies the cblk by its frameCount header field (word 42 = byte 168 =
// AudioRecord.bufferSizeInFrames) rather than a hardcoded byte size — the ashmem size varies with the
// sample rate, so size matching broke at 48 kHz. -1 if not found.
extern "C" JNIEXPORT jint JNICALL
Java_com_baba_callvault_services_recording_handoff_AudioHandoffNative_nativeFindCblkFd(JNIEnv *, jclass, jint expectedFrameCount) {
    DIR *d = opendir("/proc/self/fd");
    if (!d) return -1;
    struct dirent *e;
    char p[64], t[256];
    int result = -1;
    int skippedInvalid = 0;
    const uint32_t fc = static_cast<uint32_t>(expectedFrameCount);
    while ((e = readdir(d)) != nullptr) {
        if (e->d_name[0] == '.') continue;
        snprintf(p, sizeof(p), "/proc/self/fd/%s", e->d_name);
        ssize_t n = readlink(p, t, sizeof(t) - 1);
        if (n <= 0) continue;
        t[n] = 0;
        if (!strstr(t, "ashmem")) continue;
        int fd = atoi(e->d_name);
        int sz = ioctl(fd, 0x00007704 /*ASHMEM_GET_SIZE*/, 0);
        if (sz < 200) continue;                                   // too small to hold a cblk header
        void *m = mmap(nullptr, sz, PROT_READ, MAP_SHARED, fd, 0);
        if (m == MAP_FAILED) continue;
        uint32_t w42 = static_cast<volatile uint32_t *>(m)[42];   // frameCount field
        int32_t flags = static_cast<volatile int32_t *>(m)[44];   // audio_track_cblk_t::mFlags
        munmap(m, sz);
        if (w42 != fc) continue;
        // A block AudioFlinger has already invalidated belongs to a track that is gone — a previous call's,
        // still mapped because some fd kept it alive. Handing it over fails the new capture on its first
        // read with CBLK_INVALID and 0 bytes (every second call on a Redmi Note 10 Pro, 2026-09-27).
        if (flags & 0x04 /* CBLK_INVALID */) { skippedInvalid++; LOGI("nativeFindCblkFd: fd=%d is an invalidated block; skipped", fd); continue; }
        result = dup(fd); LOGI("nativeFindCblkFd: fd=%d sz=%d w42=%u -> dup=%d (skipped %d invalidated)", fd, sz, w42, result, skippedInvalid); break;
    }
    closedir(d);
    if (result < 0) LOGI("nativeFindCblkFd: no ashmem with frameCount=%u found", fc);
    return result;
}


// Phase-3 productionization: drain the surviving cblk ring and stream ORDERED interleaved PCM-16 to a
// pipe write-fd until STOPPED, keeping the track alive (w46=w47) exactly like nativeMonitorCblk. The app
// reads the pipe and encodes (MediaCodec + MediaMuxer) to the output file — the encode runs ENTIRELY in
// the app process, so it survives the daemon dying. `writeFd` ownership is transferred here: we close it
// on exit so the reader sees EOF and finalises the container. Ring geometry: frames of `frameSize` bytes
// (=2 mono, 4 stereo) start at byte `dataOff`; `frameCount` frames wrap the ring. `guardFrames` leaves
// the freshest N frames UNREAD each cycle (they may still be mid-write by the server) — killing torn-read
// glitches at the write cursor; we only advance mFront (w46) to what we actually consumed so the server
// won't overwrite guarded, not-yet-read frames.
//
// Stop control: `stopFlag` is a direct ByteBuffer whose first int the app flips to non-zero on call-end;
// we check it each cycle and exit cleanly. `maxSeconds` is a safety cap (max call length) so a lost stop
// signal can't drain forever. Replaces Phase-2's fixed duration — the app now owns the recording length.
// Why the drain ended. Returned to Kotlin so it reaches the app's OWN log file, which survives; the
// LOGI lines below go only to logcat, whose 256 KiB ring rotates within minutes of the event and is
// therefore empty by the time a user exports a report. Five field reports in a row failed to capture
// the reason for exactly that reason. Keep these values in sync with AudioHandoffNative.DrainExit.
#define DRAIN_EXIT_STOPPED      0   // stop flag flipped — the normal end of a recording
#define DRAIN_EXIT_INVALIDATED  1   // AudioFlinger tore the track down (CBLK_INVALID)
#define DRAIN_EXIT_STALLED      2   // server stopped advancing the ring
#define DRAIN_EXIT_MMAP_FAILED  3   // could not map the control block at all
#define DRAIN_EXIT_MAX_SECONDS  4   // ran to the safety cap without a stop signal
#define DRAIN_EXIT_PIPE_BROKEN  5   // the encoder went away; nothing is reading what we write

extern "C" JNIEXPORT jint JNICALL
Java_com_baba_callvault_services_recording_handoff_AudioHandoffNative_nativeDrainToPipe(
        JNIEnv *env, jclass, jint fd, jint size, jint frameCount, jint dataOff, jint frameSize,
        jint guardFrames, jint writeFd, jobject stopFlag, jint maxSeconds, jboolean keepPipeOpen,
        jobject statsOut) {
    // statsOut: a direct ByteBuffer of four int64s the caller reads back afterwards —
    // {bytesStreamed, droppedFrames, overrunEvents, elapsedMs}. Everything below is also LOGI'd, but
    // logcat is a 256 KiB ring that rotates within minutes and a user exports a report long after the
    // event, so anything only logged there is lost. These four numbers are what makes a short recording
    // explicable after the fact.
    // keepPipeOpen: the caller intends to resume into the SAME pipe with a fresh control block after
    // an abnormal exit, so the encoder must NOT see EOF. Closing here would finalise the container and
    // turn a recoverable mid-call track loss into the truncated file it used to be. On a clean stop
    // the pipe is still closed here, because that IS the end of the recording.
    void *base = mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (base == MAP_FAILED) { LOGI("drainToPipe: mmap FAILED"); if (!keepPipeOpen) close(writeFd); return DRAIN_EXIT_MMAP_FAILED; }
    int exitReason = DRAIN_EXIT_MAX_SECONDS;   // overwritten by whichever break fires below
    auto *stop = static_cast<volatile int32_t *>(env->GetDirectBufferAddress(stopFlag));
    auto *w = reinterpret_cast<volatile uint32_t *>(base);
    auto *frontPtr = reinterpret_cast<volatile uint32_t *>(w + 46); // mFront
    auto *rearPtr = reinterpret_cast<volatile uint32_t *>(w + 47);  // mRear
    auto *ring = static_cast<volatile uint8_t *>(base) + dataOff;
    const uint32_t fc = static_cast<uint32_t>(frameCount);
    const uint32_t guard = static_cast<uint32_t>(guardFrames);
    const int fsz = frameSize;                // bytes per frame (2 mono, 4 stereo)
    // CRITICAL: the physical ring wraps at P2 = roundup(frameCount) to the next power of 2, and positions
    // are masked with (P2-1) — NOT modulo frameCount. This is what AudioRecordClientProxy::obtainBuffer
    // does (front &= mFrameCountP2-1). Wrapping at frameCount for non-power-of-2 sizes (all 48kHz configs)
    // read the wrong/zeroed region between frameCount and P2 => the gaps. (16kHz mono fc=2048 was already
    // a power of 2, which is why only it sounded clean.)
    uint32_t p2 = 1; while (p2 < fc) p2 <<= 1;
    const uint32_t mask = p2 - 1;

    // DECOUPLE ring consumption from downstream: the consumer MUST advance mFront on a steady cadence,
    // never blocking on the pipe. If it blocks (encoder/file backpressure), mFront stalls, the server laps
    // the ring (overrun), and we read corrupted/zeroed frames = periodic micro-gaps (the choppiness). So we
    // copy available frames into a heap stage + advance mFront IMMEDIATELY, then drain the stage to the
    // pipe NON-BLOCKING. A pipe stall only grows RAM, never stalls the ring read.
    fcntl(writeFd, F_SETFL, O_NONBLOCK);
    std::vector<uint8_t> stage; size_t drainOff = 0;
    // A write error that is NOT "try again" means the reader is gone for good — the encoder thread
    // died or was torn down. This used to be an unqualified `return`, which left the loop running: the
    // stage was never drained, never cleared, and grew at ~192 KB/s for the remaining maxSeconds. Four
    // hours of that is gigabytes of native heap and eventually a process abort, all while the app still
    // believed it was recording. Now it ends the drain and says why.
    bool pipeBroken = false;
    auto pumpPipe = [&](bool finalFlush) {
        while (drainOff < stage.size()) {
            ssize_t n = write(writeFd, stage.data() + drainOff, stage.size() - drainOff);
            if (n > 0) { drainOff += static_cast<size_t>(n); }
            else if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
                if (!finalFlush) return;          // pipe full — keep it staged, retry next cycle
                usleep(2000); continue;           // final flush: wait for the reader to catch up
            } else {
                LOGI("drainToPipe: pipe write failed (errno=%d) — the reader is gone", errno);
                pipeBroken = true;
                return;
            }
        }
        stage.clear(); drainOff = 0;              // fully drained
    };

    uint32_t lastFront = *rearPtr;            // start from 'now' (server rear)
    long totalBytes = 0; int tick = 0; uint32_t prevRear = lastFront;
    const int CYCLE_US = 5000;                // 5 ms — keep ring occupancy tiny (<< the ring's ms)
    const int cyclesPerSec = 1000000 / CYCLE_US; // 200
    const long maxCycles = static_cast<long>(maxSeconds) * cyclesPerSec;

    // ---- Liveness of the handed-off track ------------------------------------------------------
    // We hold the cblk but NOT an AudioRecord object — that lives in the daemon, which by design may
    // be dead. AudioRecord::restoreRecord_l (the silent rebuild after an invalidation) is therefore
    // unreachable to us, and EVENT_NEW_IAUDIORECORD is never dispatched on the record path. So when
    // AudioFlinger invalidates the track we get NO exception, NO callback and NO dead binder — the
    // ring simply freezes and the recording truncates silently.
    //
    // Two independent detectors, both diagnostic: they end the drain cleanly (flush + EOF, so the
    // container still finalises) and say WHY in the log, instead of spinning to maxSeconds.
    auto *flagsPtr = reinterpret_cast<volatile int32_t *>(w + 44);  // audio_track_cblk_t::mFlags
    // Offsets cross-check against the empirically pinned geometry: mFlags(44), mState(45) sit
    // immediately before the union whose first member mFront is the verified word 46.
    const int32_t CBLK_INVALID_FLAG = 0x04;   // AudioTrackShared.h: "invalidated by AudioFlinger"
    // The server writes continuously while capturing — even a SILENCED track advances mRear at full
    // rate (silencing memsets the buffer, it does not stop the stream). So a rear that stops moving
    // means the stream itself is gone, not that the call went quiet. Generous, to never cut a healthy
    // recording short over a transient HAL hiccup.
    const int STALL_LIMIT_CYCLES = 10 * cyclesPerSec;
    uint32_t stallRear = lastFront;
    long stallSinceCycle = 0;
    // A real clock. Every "t~Ns" used to be i/cyclesPerSec, which assumes each iteration takes exactly
    // CYCLE_US — it does not, because the copy, the pipe write and scheduling all add to it. Those
    // timestamps understated elapsed time, and any reconciliation built on them would inherit the error.
    struct timespec tsStart{};
    clock_gettime(CLOCK_MONOTONIC, &tsStart);
    auto elapsedMs = [&]() -> int64_t {
        struct timespec now{};
        clock_gettime(CLOCK_MONOTONIC, &now);
        return (now.tv_sec - tsStart.tv_sec) * 1000LL + (now.tv_nsec - tsStart.tv_nsec) / 1000000LL;
    };
    int64_t droppedFrames = 0;   // audio thrown away on overrun — silent loss until now
    int overrunEvents = 0;
    LOGI("drainToPipe: start frameCount=%u P2=%u dataOff=%d frameSize=%d guard=%u maxSec=%d cycle=%dus (decoupled)", fc, p2, dataOff, fsz, guard, maxSeconds, CYCLE_US);
    for (long i = 0; i < maxCycles; i++) {
        if (pipeBroken) { exitReason = DRAIN_EXIT_PIPE_BROKEN; break; }
        if (stop && __atomic_load_n(stop, __ATOMIC_ACQUIRE) != 0) { LOGI("drainToPipe: stop requested at t=%lldms", (long long) elapsedMs()); exitReason = DRAIN_EXIT_STOPPED; break; }
        uint32_t rear = __atomic_load_n(rearPtr, __ATOMIC_ACQUIRE);  // acquire: data reads see server's release

        // Definitive: AudioFlinger has torn the track down (input preempted at maxOpenCount, route
        // close, audioserver restart). Nothing in this process can rebuild it — stop and report.
        if (__atomic_load_n(flagsPtr, __ATOMIC_RELAXED) & CBLK_INVALID_FLAG) {
            LOGI("drainToPipe: TRACK INVALIDATED by AudioFlinger (CBLK_INVALID) at t~%lds after %ld bytes "
                 "— recording ends here", i / cyclesPerSec, totalBytes);
            exitReason = DRAIN_EXIT_INVALIDATED;
            break;
        }
        // Belt-and-braces: the stream stopped without the flag being set for us to see.
        if (rear != stallRear) { stallRear = rear; stallSinceCycle = i; }
        else if (i - stallSinceCycle > STALL_LIMIT_CYCLES) {
            LOGI("drainToPipe: RING STALLED %ds at rear=%u after %ld bytes (capture stopped upstream) "
                 "— recording ends here", (int) ((i - stallSinceCycle) / cyclesPerSec), rear, totalBytes);
            exitReason = DRAIN_EXIT_STALLED;
            break;
        }
        uint32_t safeRear = (rear - lastFront > guard) ? rear - guard : lastFront; // hold back freshest
        uint32_t avail = safeRear - lastFront;     // unsigned wrap-safe frame count
        // Overrun: the drain fell more than a ring behind, so everything older than one ring is gone.
        // The encoder inserts no silence for it, so the timeline COMPRESSES — the file comes out short
        // rather than gappy, which looks exactly like truncation. Counted so it can be reported.
        if (avail > fc) { droppedFrames += (avail - fc); overrunEvents++; avail = fc; }
        if (avail > 0) {
            uint32_t startIdx = lastFront & mask;              // physical position (wrap at P2, not fc)
            uint32_t firstFrames = p2 - startIdx;              // contiguous to the physical buffer end
            if (firstFrames > avail) firstFrames = avail;
            const uint8_t *r1 = const_cast<const uint8_t *>(ring + startIdx * fsz);
            stage.insert(stage.end(), r1, r1 + static_cast<size_t>(firstFrames) * fsz);
            uint32_t rem = avail - firstFrames;
            if (rem > 0) {
                const uint8_t *r2 = const_cast<const uint8_t *>(ring); // wrapped tail from buffer start
                stage.insert(stage.end(), r2, r2 + static_cast<size_t>(rem) * fsz);
            }
            totalBytes += static_cast<long>(avail) * fsz;
        }
        lastFront = safeRear;
        __atomic_store_n(frontPtr, safeRear, __ATOMIC_RELEASE); // advance mFront NOW (before any pipe I/O)
        pumpPipe(false);                          // best-effort non-blocking drain
        if (drainOff > (1u << 20)) { stage.erase(stage.begin(), stage.begin() + drainOff); drainOff = 0; }
        if ((i + 1) % cyclesPerSec == 0) {
            LOGI("drainToPipe t=%2ds rear=%u (+%u) bytes=%ld staged=%zu", tick, rear, rear - prevRear, totalBytes, stage.size() - drainOff);
            prevRear = rear; tick++;
        }
        usleep(CYCLE_US);
    }
    pumpPipe(true);                           // flush remaining stage (blocking-ish) before EOF
    // Close only when this really is the end. A resumable exit leaves the pipe open so the next drain
    // writes into the same stream and the encoder never notices the seam.
    const bool resumable = (exitReason != DRAIN_EXIT_STOPPED);
    if (!(keepPipeOpen && resumable)) close(writeFd);   // EOF -> reader finalises the container
    munmap(base, size);
    LOGI("drainToPipe: done, %ld PCM bytes streamed (exit=%d, dropped=%lld frames in %d overruns, %lldms)",
         totalBytes, exitReason, (long long) droppedFrames, overrunEvents, (long long) elapsedMs());
    if (statsOut != nullptr) {
        auto *s = static_cast<int64_t *>(env->GetDirectBufferAddress(statsOut));
        if (s != nullptr && env->GetDirectBufferCapacity(statsOut) >= 32) {
            s[0] = (int64_t) totalBytes;
            s[1] = droppedFrames;
            s[2] = (int64_t) overrunEvents;
            s[3] = elapsedMs();
        }
    }
    return exitReason;
}
