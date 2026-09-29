# Dynamic audio route switching (speakerphone / earpiece) and call idle debounce

**Status: COMPLETED.** Verified in production on Xiaomi POCO X7 Pro (*rodin*, Dimensity 8400-Ultra / MediaTek HAL, HyperOS / Android 16).

## What happened

Users reported that carrier call recordings would frequently truncate, cut short, or fail to record the remainder of a conversation when switching between the earpiece and speakerphone mid-call. In real-world tests, a 45-second call would only record 23 seconds—stopping precisely at the second the speakerphone button was pressed, while the cellular phone call itself continued uninterrupted.

Additionally, secondary incoming calls (call waiting) or transient modem state transitions could prematurely trigger a session stop, leaving recordings truncated or missing.

## Root Cause Analysis

### 1. `CBLK_INVALID` in the native handoff pipeline (`AudioHandoffNative`)
When "Resilient recording" (Option B, audio-capture handoff) was enabled, the app process drained the shared memory ring buffer (`cblk`) directly from native code (`audiohandoff.cpp`).
On modern Android HALs (particularly MediaTek AudioPolicy), switching audio routes from `AUDIO_DEVICE_OUT_EARPIECE` to `AUDIO_DEVICE_OUT_SPEAKER` tears down the active hardware input stream. AudioFlinger marks the control block with `CBLK_INVALID_FLAG` (`0x04`).

In `audiohandoff.cpp`:
```cpp
if (__atomic_load_n(flagsPtr, __ATOMIC_RELAXED) & CBLK_INVALID_FLAG) {
    LOGI("drainToPipe: TRACK INVALIDATED by AudioFlinger (CBLK_INVALID) at t~%lds after %ld bytes "
         "— recording ends here", i / cyclesPerSec, totalBytes);
    break;
}
```
Because the unprivileged app process cannot recreate a privileged `AudioRecord`, it had no choice but to break out and terminate recording immediately, finalizing the file prematurely.

### 2. Lack of dynamic reconnection in `DirectAudioRecorderSession`
In direct capture mode (`DirectAudioRecorderSession`), an invalidated track caused `read()` to return `<= 0` or negative error codes. Without a route recovery mechanism, consecutive read errors would eventually terminate capture or stall the pipeline.

### 3. Immediate termination on `CALL_STATE_IDLE`
`CallSessionManager` previously executed `ACTION_STOP_RECORDING` immediately upon receiving `CALL_STATE_IDLE`. Any transient glitch or secondary call rejection could signal a temporary IDLE broadcast, killing an ongoing call's recording.

## Resolution

### 1. Route-switch hot-reconnection in `DirectAudioRecorderSession`
In `DirectAudioRecorderSession.kt`, consecutive read failures (`read <= 0`) trigger a dynamic reconnection loop:
- The existing (invalidated) `AudioRecord` is stopped and released.
- A 2000 ms retry window attempts to re-open `openAudioRecord(androidSource)` every 150 ms until the new hardware route (speakerphone/earpiece) is established and enters `RECORDSTATE_RECORDING`.
- Capture seamlessly resumes into the **same** `MediaCodec` encoder and `MediaMuxer` container with continuous sample-count presentation timestamps (PTS), producing a single uninterrupted audio file.

### 2. Disabling fragile handoff in `HandoffPolicy`
In `HandoffPolicy.kt`, `isUsable` returns `false` to ensure all recordings route through `DirectAudioRecorderSession` (hosted inside the privileged daemon, UID 2000), which has the required system permissions to rebuild the track on route changes.

### 3. Grace period debounce in `CallSessionManager`
In `CallSessionManager.kt`:
- Added `IDLE_DEBOUNCE_MS = 2000L`.
- When `CALL_STATE_IDLE` is received, `idleDebounceJob` delays 2000 ms before stopping.
- If `TelephonyManager.callState` is still `CALL_STATE_OFFHOOK` after the delay (e.g. call waiting dismissed), the stop is cancelled and recording proceeds.
