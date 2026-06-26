# Dialer Mode — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an optional "dialer + recording" mode: make CallVault selectable as the default phone app, render its own incoming/in-call/dialpad UI, and detect calls via Telecom (`InCallService`) instead of broadcasts — feeding the **unchanged** recording pipeline.

**Architecture:** A `CallEventSource` seam sits between call detection and the existing recording decision. Two implementations (`BroadcastCallEventSource` = today's path; `TelecomCallEventSource` = new `InCallService`) emit identical normalized `CallEvent`s into a single `CallEventRouter`, which forwards to the existing `CallSessionManager`. Mode is a reversible preference gated on the real `ROLE_DIALER` role.

**Tech Stack:** Kotlin, Android (minSdk 30 / targetSdk 36), Jetpack Compose, Android Telecom (`InCallService`, `TelecomManager`, `RoleManager`). Tests: JUnit4 + MockK + Robolectric (added in Task 0).

## Global Constraints

- **minSdk 30, targetSdk 36** — all APIs must be guarded for API 30 where they require ≥31.
- **Recording-only mode must remain byte-for-byte identical to v1.2.1.** `DIALER_MODE_ENABLED` defaults to `false`; with it off, no behavior changes.
- **Recording pipeline is UNCHANGED.** Do not modify `shouldAutoRecord`, the daemon, `RecorderServerLauncher`, or `RecordingForegroundService`'s recording logic. Only the *source* of call events changes.
- **Translation gate:** lint treats `MissingTranslation`/`ExtraTranslation` as errors. Every new user-facing string added to `values/` MUST be added to all 9 locale folders (`values-fr,de,es,it,hu,pl,ru,vi,zh-rCN`) or `lintDebug` fails.
- **Emergency calls always work** and are never filtered, blocked, or record-gated.
- **Role truth = `RoleManager.isRoleHeld(ROLE_DIALER)`**, never the stored preference alone.
- **No new signing/release changes.** Builds stay debug-signed per current release process.
- **Build env:** `JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home` for local Gradle.
- Conventional commits; commit after each task.

---

## File Structure

**New (pure logic — JVM unit-tested):**
- `app/src/main/java/com/baba/callvault/calls/CallEvent.kt` — normalized event model.
- `app/src/main/java/com/baba/callvault/calls/CallEventSource.kt` — source interface + listener type.
- `app/src/main/java/com/baba/callvault/calls/CallEventRouter.kt` — mode-aware single sink → `CallSessionManager`.
- `app/src/main/java/com/baba/callvault/calls/CallStateRepository.kt` — process-wide current-call state for the UI.

**New (Android glue — thin, on-device verified):**
- `app/src/main/java/com/baba/callvault/dialer/DialerRoleController.kt` — `RoleManager` wrapper.
- `app/src/main/java/com/baba/callvault/dialer/CallVaultInCallService.kt` — `InCallService`.
- `app/src/main/java/com/baba/callvault/dialer/TelecomCallEventSource.kt` — Telecom `Call` → `CallEvent`.
- `app/src/main/java/com/baba/callvault/dialer/BroadcastCallEventSource.kt` — wraps existing detection.

**New (Compose UI — on-device verified):**
- `app/src/main/java/com/baba/callvault/ui/dialer/InCallActivity.kt`
- `app/src/main/java/com/baba/callvault/ui/dialer/InCallScreen.kt`
- `app/src/main/java/com/baba/callvault/ui/dialer/DialpadScreen.kt`
- `app/src/main/java/com/baba/callvault/ui/dialer/InCallViewModel.kt`

**Modified:**
- `app/build.gradle.kts` — test deps (Task 0).
- `app/src/main/java/com/baba/callvault/data/AppPreferences.kt` — `DIALER_MODE_ENABLED`.
- `app/src/main/java/com/baba/callvault/services/call/CallSessionManager.kt` — expose a clean entry for `CallEventRouter`; add dialer-mode deferral.
- `app/src/main/java/com/baba/callvault/services/call/PhoneStateReceiver.kt` — defer when dialer mode active.
- `app/src/main/AndroidManifest.xml` — `InCallService`, `InCallActivity`, permissions.
- `app/src/main/java/com/baba/callvault/ui/screens/SettingsScreen.kt` + `ui/viewmodels/SettingsViewModel.kt` — mode toggle + role flow + banner.
- `app/src/main/res/values/strings_dialer.xml` (new) + all 9 locale copies — new strings.

---

## Task 0: Add JVM unit-test harness

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/test/java/com/baba/callvault/SanityTest.kt`

**Interfaces:**
- Produces: a working `./gradlew :app:testDebugUnitTest` task for all later logic tasks.

- [ ] **Step 1: Add test dependencies** to `app/build.gradle.kts` dependencies block:

```kotlin
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.robolectric:robolectric:4.14")
    testImplementation("androidx.test:core:1.6.1")
```

- [ ] **Step 2: Enable Android resources in unit tests** — inside the `android { ... }` block:

```kotlin
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
```

- [ ] **Step 3: Write the sanity test** at `app/src/test/java/com/baba/callvault/SanityTest.kt`:

```kotlin
package com.baba.callvault

import org.junit.Assert.assertEquals
import org.junit.Test

class SanityTest {
    @Test fun harness_runs() { assertEquals(4, 2 + 2) }
}
```

- [ ] **Step 4: Run it**

Run: `JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :app:testDebugUnitTest --tests "com.baba.callvault.SanityTest"`
Expected: PASS (BUILD SUCCESSFUL).

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/test/java/com/baba/callvault/SanityTest.kt
git commit -m "test: add JVM unit-test harness (JUnit/MockK/Robolectric)"
```

---

## Task 1: Normalized `CallEvent` model + `CallEventSource` interface

**Files:**
- Create: `app/src/main/java/com/baba/callvault/calls/CallEvent.kt`
- Create: `app/src/main/java/com/baba/callvault/calls/CallEventSource.kt`
- Test: `app/src/test/java/com/baba/callvault/calls/CallEventTest.kt`

**Interfaces:**
- Produces:
  - `enum class CallDirection { INCOMING, OUTGOING, UNKNOWN }`
  - `data class CallEvent(val phase: Phase, val number: String?, val direction: CallDirection, val isEmergency: Boolean)` with `enum class Phase { RINGING, DIALING, ACTIVE, ENDED }`
  - `fun interface CallEventListener { fun onCallEvent(event: CallEvent) }`
  - `interface CallEventSource { fun start(listener: CallEventListener); fun stop() }`

- [ ] **Step 1: Write the failing test** at `CallEventTest.kt`:

```kotlin
package com.baba.callvault.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallEventTest {
    @Test fun builds_incoming_ringing_event() {
        val e = CallEvent(CallEvent.Phase.RINGING, "+15551234", CallDirection.INCOMING, isEmergency = false)
        assertEquals(CallEvent.Phase.RINGING, e.phase)
        assertEquals(CallDirection.INCOMING, e.direction)
        assertEquals("+15551234", e.number)
        assertTrue(!e.isEmergency)
    }

    @Test fun emergency_flag_is_carried() {
        val e = CallEvent(CallEvent.Phase.DIALING, "911", CallDirection.OUTGOING, isEmergency = true)
        assertTrue(e.isEmergency)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.baba.callvault.calls.CallEventTest"`
Expected: FAIL (unresolved reference `CallEvent`).

- [ ] **Step 3: Implement** `CallEvent.kt`:

```kotlin
package com.baba.callvault.calls

enum class CallDirection { INCOMING, OUTGOING, UNKNOWN }

/** A call-lifecycle event normalized across detection sources (broadcast vs Telecom). */
data class CallEvent(
    val phase: Phase,
    val number: String?,
    val direction: CallDirection,
    val isEmergency: Boolean,
) {
    enum class Phase { RINGING, DIALING, ACTIVE, ENDED }
}
```

- [ ] **Step 4: Implement** `CallEventSource.kt`:

```kotlin
package com.baba.callvault.calls

/** Receives normalized call events from whichever source is active. */
fun interface CallEventListener {
    fun onCallEvent(event: CallEvent)
}

/** A source of normalized call events. Exactly one source is active at a time (see CallEventRouter). */
interface CallEventSource {
    fun start(listener: CallEventListener)
    fun stop()
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.baba.callvault.calls.CallEventTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/baba/callvault/calls/CallEvent.kt app/src/main/java/com/baba/callvault/calls/CallEventSource.kt app/src/test/java/com/baba/callvault/calls/CallEventTest.kt
git commit -m "feat(calls): normalized CallEvent model + CallEventSource interface"
```

---

## Task 2: `CallEventRouter` — mode-aware single sink

The router is the one place that knows the current mode. It forwards events to a sink callback
(wired to `CallSessionManager` in Task 5/6). It ignores events from the non-active source.

**Files:**
- Create: `app/src/main/java/com/baba/callvault/calls/CallEventRouter.kt`
- Test: `app/src/test/java/com/baba/callvault/calls/CallEventRouterTest.kt`

**Interfaces:**
- Consumes: `CallEvent`, `CallDirection` (Task 1).
- Produces:
  - `enum class DetectionMode { BROADCAST, TELECOM }`
  - `class CallEventRouter(private val sink: (CallEvent) -> Unit)` with:
    - `fun setActiveMode(mode: DetectionMode)`
    - `fun submit(source: DetectionMode, event: CallEvent)` — forwards to `sink` only if `source == active mode`.

- [ ] **Step 1: Write the failing test** at `CallEventRouterTest.kt`:

```kotlin
package com.baba.callvault.calls

import org.junit.Assert.assertEquals
import org.junit.Test

class CallEventRouterTest {
    private fun event() = CallEvent(CallEvent.Phase.ACTIVE, "1", CallDirection.INCOMING, false)

    @Test fun forwards_events_from_active_source_only() {
        val seen = mutableListOf<CallEvent>()
        val router = CallEventRouter(sink = { seen.add(it) })
        router.setActiveMode(DetectionMode.TELECOM)

        router.submit(DetectionMode.TELECOM, event())   // active → forwarded
        router.submit(DetectionMode.BROADCAST, event())  // inactive → dropped

        assertEquals(1, seen.size)
    }

    @Test fun switching_mode_changes_which_source_is_forwarded() {
        val seen = mutableListOf<CallEvent>()
        val router = CallEventRouter(sink = { seen.add(it) })
        router.setActiveMode(DetectionMode.BROADCAST)
        router.submit(DetectionMode.BROADCAST, event())
        router.setActiveMode(DetectionMode.TELECOM)
        router.submit(DetectionMode.BROADCAST, event())  // now inactive → dropped
        assertEquals(1, seen.size)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.baba.callvault.calls.CallEventRouterTest"`
Expected: FAIL (unresolved `CallEventRouter`).

- [ ] **Step 3: Implement** `CallEventRouter.kt`:

```kotlin
package com.baba.callvault.calls

import java.util.concurrent.atomic.AtomicReference

enum class DetectionMode { BROADCAST, TELECOM }

/**
 * Single sink for normalized call events. Only events from the currently active detection mode are
 * forwarded; the inactive source is ignored. This enforces broadcast↔Telecom mutual exclusion.
 */
class CallEventRouter(private val sink: (CallEvent) -> Unit) {
    private val active = AtomicReference(DetectionMode.BROADCAST)

    fun setActiveMode(mode: DetectionMode) { active.set(mode) }

    fun submit(source: DetectionMode, event: CallEvent) {
        if (source == active.get()) sink(event)
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.baba.callvault.calls.CallEventRouterTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/baba/callvault/calls/CallEventRouter.kt app/src/test/java/com/baba/callvault/calls/CallEventRouterTest.kt
git commit -m "feat(calls): mode-aware CallEventRouter with source mutual-exclusion"
```

---

## Task 3: `DIALER_MODE_ENABLED` preference

**Files:**
- Modify: `app/src/main/java/com/baba/callvault/data/AppPreferences.kt`
- Test: `app/src/test/java/com/baba/callvault/data/DialerModePrefTest.kt`

**Interfaces:**
- Produces (on `AppPreferences`): `fun isDialerModeEnabled(): Boolean` (default `false`), `fun setDialerModeEnabled(enabled: Boolean)`.

- [ ] **Step 1: Inspect existing pattern** — open `AppPreferences.kt`, find the `Key` enum, the defaults, and an existing boolean accessor pair (e.g. `isDynamicColorEnabled`/`setDynamicColorEnabled`). Mirror it exactly.

- [ ] **Step 2: Write the failing test** at `DialerModePrefTest.kt`:

```kotlin
package com.baba.callvault.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DialerModePrefTest {
    private val prefs = AppPreferences(ApplicationProvider.getApplicationContext())

    @Test fun defaults_to_false() { assertFalse(prefs.isDialerModeEnabled()) }

    @Test fun persists_true() {
        prefs.setDialerModeEnabled(true)
        assertTrue(prefs.isDialerModeEnabled())
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.baba.callvault.data.DialerModePrefTest"`
Expected: FAIL (unresolved `isDialerModeEnabled`).

- [ ] **Step 4: Implement** — add a `DIALER_MODE_ENABLED("dialer_mode_enabled")` entry to the `Key` enum, a `false` default consistent with how other boolean defaults are declared, and the accessor pair mirroring `isDynamicColorEnabled`/`setDynamicColorEnabled`:

```kotlin
    fun isDialerModeEnabled(): Boolean =
        sharedPreferences.getBoolean(Key.DIALER_MODE_ENABLED.id, false)

    fun setDialerModeEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(Key.DIALER_MODE_ENABLED.id, enabled).apply()
    }
```
(Adjust property/field names — `sharedPreferences`, `Key.id` — to match the actual file.)

- [ ] **Step 5: Run to verify it passes**

Run: `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.baba.callvault.data.DialerModePrefTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/baba/callvault/data/AppPreferences.kt app/src/test/java/com/baba/callvault/data/DialerModePrefTest.kt
git commit -m "feat(prefs): add DIALER_MODE_ENABLED preference (default off)"
```

---

## Task 4: `DialerRoleController` (RoleManager wrapper)

Thin wrapper over `RoleManager`. The only unit-testable logic is "is the role held?" and the intent
factory; the actual grant happens via a system Activity result (verified on-device in Task 10).

**Files:**
- Create: `app/src/main/java/com/baba/callvault/dialer/DialerRoleController.kt`
- Test: `app/src/test/java/com/baba/callvault/dialer/DialerRoleControllerTest.kt`

**Interfaces:**
- Produces:
  - `class DialerRoleController(private val context: Context)`
  - `fun isDefaultDialer(): Boolean` — `RoleManager.isRoleHeld(ROLE_DIALER)`; `false` if RoleManager unavailable.
  - `fun requestRoleIntent(): Intent?` — `RoleManager.createRequestRoleIntent(ROLE_DIALER)`; `null` if already held or unavailable.
  - `fun releaseRoleHint(): Intent` — intent to system default-apps settings (we cannot programmatically drop the role; we send the user to settings).

- [ ] **Step 1: Write the failing test** (Robolectric provides a `RoleManager`):

```kotlin
package com.baba.callvault.dialer

import android.app.role.RoleManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class DialerRoleControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val controller = DialerRoleController(context)

    @Test fun reports_not_default_when_role_not_held() {
        // Robolectric default: role not held
        org.junit.Assert.assertFalse(controller.isDefaultDialer())
    }

    @Test fun request_intent_is_available_when_not_held() {
        assertNotNull(controller.requestRoleIntent())
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.baba.callvault.dialer.DialerRoleControllerTest"`
Expected: FAIL (unresolved `DialerRoleController`).

- [ ] **Step 3: Implement** `DialerRoleController.kt`:

```kotlin
package com.baba.callvault.dialer

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Wraps RoleManager for the default-dialer (ROLE_DIALER) role. Truth = isRoleHeld, never a preference. */
class DialerRoleController(private val context: Context) {

    private val roleManager: RoleManager? =
        context.getSystemService(Context.ROLE_SERVICE) as? RoleManager

    fun isDefaultDialer(): Boolean {
        val rm = roleManager ?: return false
        return rm.isRoleAvailable(RoleManager.ROLE_DIALER) && rm.isRoleHeld(RoleManager.ROLE_DIALER)
    }

    /** Intent to ask the user to make CallVault the default phone app, or null if already held/unavailable. */
    fun requestRoleIntent(): Intent? {
        val rm = roleManager ?: return null
        if (!rm.isRoleAvailable(RoleManager.ROLE_DIALER) || rm.isRoleHeld(RoleManager.ROLE_DIALER)) return null
        return rm.createRequestRoleIntent(RoleManager.ROLE_DIALER)
    }

    /** We cannot drop the role programmatically; send the user to default-apps settings. */
    fun releaseRoleHint(): Intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.baba.callvault.dialer.DialerRoleControllerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/baba/callvault/dialer/DialerRoleController.kt app/src/test/java/com/baba/callvault/dialer/DialerRoleControllerTest.kt
git commit -m "feat(dialer): DialerRoleController wrapping RoleManager(ROLE_DIALER)"
```

---

## Task 5: Wire the existing detection through the router (recording-only unchanged)

Make the current broadcast path emit through `CallEventRouter` without changing behavior. Provide a
process-wide singleton holder so both the receiver and (later) the InCallService share one router +
sink. The sink calls the existing `CallSessionManager` entry point.

**Files:**
- Create: `app/src/main/java/com/baba/callvault/calls/CallDetection.kt` (singleton holder + sink wiring)
- Create: `app/src/main/java/com/baba/callvault/dialer/BroadcastCallEventSource.kt`
- Modify: `app/src/main/java/com/baba/callvault/services/call/CallSessionManager.kt` (add a thin `onCallEvent(CallEvent)` adapter that maps to the existing `handlePhoneState`/`processSessionUpdate` inputs — do not change the decision logic)
- Test: `app/src/test/java/com/baba/callvault/calls/CallDetectionSinkTest.kt`

**Interfaces:**
- Consumes: `CallEventRouter`, `DetectionMode`, `CallEvent` (Tasks 1–2); `AppPreferences.isDialerModeEnabled` (Task 3).
- Produces:
  - `object CallDetection { val router: CallEventRouter; fun init(context: Context); fun setMode(dialer: Boolean) }`
  - `CallSessionManager.onCallEvent(event: CallEvent)` — adapter to the existing pipeline.

- [ ] **Step 1: Inspect** `CallSessionManager.handlePhoneState(...)` and `processSessionUpdate(...)` signatures. Identify the minimal inputs (state string/number/direction) so the adapter maps `CallEvent` → existing call without touching `shouldAutoRecord`.

- [ ] **Step 2: Write the failing test** — verify the router forwards a broadcast-sourced event to a sink (the adapter is exercised on-device; here we test the holder wiring with a fake sink):

```kotlin
package com.baba.callvault.calls

import org.junit.Assert.assertEquals
import org.junit.Test

class CallDetectionSinkTest {
    @Test fun broadcast_source_reaches_sink_in_broadcast_mode() {
        val seen = mutableListOf<CallEvent>()
        val router = CallEventRouter(sink = { seen.add(it) })
        router.setActiveMode(DetectionMode.BROADCAST)
        val src = com.baba.callvault.dialer.BroadcastCallEventSource(router)
        src.emit(CallEvent(CallEvent.Phase.ACTIVE, "1", CallDirection.OUTGOING, false))
        assertEquals(1, seen.size)
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.baba.callvault.calls.CallDetectionSinkTest"`
Expected: FAIL (unresolved `BroadcastCallEventSource`).

- [ ] **Step 4: Implement** `BroadcastCallEventSource.kt`:

```kotlin
package com.baba.callvault.dialer

import com.baba.callvault.calls.CallEvent
import com.baba.callvault.calls.CallEventRouter
import com.baba.callvault.calls.DetectionMode

/** Adapts the existing PhoneStateReceiver/CallMonitorService path to the CallEventRouter. */
class BroadcastCallEventSource(private val router: CallEventRouter) {
    fun emit(event: CallEvent) = router.submit(DetectionMode.BROADCAST, event)
}
```

- [ ] **Step 5: Implement** `CallDetection.kt` (singleton; sink → `CallSessionManager`):

```kotlin
package com.baba.callvault.calls

import android.content.Context
import com.baba.callvault.services.call.CallSessionManager

/** Process-wide holder so broadcast + Telecom sources share one router and one sink. */
object CallDetection {
    lateinit var router: CallEventRouter
        private set

    fun init(context: Context) {
        if (::router.isInitialized) return
        val mgr = CallSessionManager.getInstance()
        router = CallEventRouter(sink = { event -> mgr.onCallEvent(event) })
    }

    fun setMode(dialer: Boolean) {
        router.setActiveMode(if (dialer) DetectionMode.TELECOM else DetectionMode.BROADCAST)
    }
}
```

- [ ] **Step 6: Add the adapter** to `CallSessionManager.kt` — a thin method mapping `CallEvent` to the existing internal handling (use the real internal method names found in Step 1; do not alter decision logic):

```kotlin
    /** Entry point for the CallEventRouter. Maps a normalized event onto the existing pipeline. */
    fun onCallEvent(event: com.baba.callvault.calls.CallEvent) {
        // Map phase → existing state handling. Emergency calls are passed through unchanged.
        // Reuse the SAME processSessionUpdate/handlePhoneState path the broadcast already uses.
        // (Wire using the inputs identified in Step 1 — number/direction/phase.)
    }
```

- [ ] **Step 7: Initialize** `CallDetection` where the app starts (in `CallVaultApplication.onCreate`, after existing init) and set initial mode from the preference:

```kotlin
        com.baba.callvault.calls.CallDetection.init(this)
        com.baba.callvault.calls.CallDetection.setMode(com.baba.callvault.data.AppPreferences(this).isDialerModeEnabled())
```

- [ ] **Step 8: Make `PhoneStateReceiver` route through the broadcast source AND defer in dialer mode.** In `PhoneStateReceiver.onReceive`, early-return when `AppPreferences(context).isDialerModeEnabled()` is true (Telecom owns detection then), preserving the existing `CallMonitorService.isListening` deferral.

- [ ] **Step 9: Run unit test + build**

Run: `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.baba.callvault.calls.CallDetectionSinkTest" :app:assembleDebug`
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 10: On-device regression** — install, confirm recording-only mode still auto-records an incoming and an outgoing call exactly as v1.2.1 (dialer mode still off).

Run: `adb -s 6011b07e install -r app/build/outputs/apk/debug/app-debug.apk` then place/receive a test call.
Expected: recordings created as before.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/com/baba/callvault/calls/CallDetection.kt app/src/main/java/com/baba/callvault/dialer/BroadcastCallEventSource.kt app/src/main/java/com/baba/callvault/services/call/CallSessionManager.kt app/src/main/java/com/baba/callvault/services/call/PhoneStateReceiver.kt app/src/main/java/com/baba/callvault/CallVaultApplication.kt app/src/test/java/com/baba/callvault/calls/CallDetectionSinkTest.kt
git commit -m "feat(calls): route broadcast detection through CallEventRouter; defer in dialer mode"
```

---

## Task 6: `CallStateRepository` — shared current-call state for the UI

**Files:**
- Create: `app/src/main/java/com/baba/callvault/calls/CallStateRepository.kt`
- Test: `app/src/test/java/com/baba/callvault/calls/CallStateRepositoryTest.kt`

**Interfaces:**
- Produces:
  - `data class UiCall(val number: String?, val phase: CallEvent.Phase, val direction: CallDirection, val isEmergency: Boolean, val isRecording: Boolean)`
  - `object CallStateRepository { val current: StateFlow<UiCall?>; fun update(call: UiCall?); fun setRecording(active: Boolean) }`

- [ ] **Step 1: Write the failing test**:

```kotlin
package com.baba.callvault.calls

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallStateRepositoryTest {
    @Test fun update_and_clear_current_call() = runBlocking {
        CallStateRepository.update(UiCall("1", CallEvent.Phase.ACTIVE, CallDirection.INCOMING, false, false))
        assertEquals("1", CallStateRepository.current.first()!!.number)
        CallStateRepository.update(null)
        assertNull(CallStateRepository.current.first())
    }

    @Test fun set_recording_updates_flag() = runBlocking {
        CallStateRepository.update(UiCall("1", CallEvent.Phase.ACTIVE, CallDirection.INCOMING, false, false))
        CallStateRepository.setRecording(true)
        assertEquals(true, CallStateRepository.current.first()!!.isRecording)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.baba.callvault.calls.CallStateRepositoryTest"`
Expected: FAIL.

- [ ] **Step 3: Implement** `CallStateRepository.kt`:

```kotlin
package com.baba.callvault.calls

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UiCall(
    val number: String?,
    val phase: CallEvent.Phase,
    val direction: CallDirection,
    val isEmergency: Boolean,
    val isRecording: Boolean,
)

/** Process-wide current-call state, shared between the InCallService and the in-call UI. */
object CallStateRepository {
    private val _current = MutableStateFlow<UiCall?>(null)
    val current: StateFlow<UiCall?> = _current.asStateFlow()

    fun update(call: UiCall?) { _current.value = call }

    fun setRecording(active: Boolean) {
        _current.value = _current.value?.copy(isRecording = active)
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.baba.callvault.calls.CallStateRepositoryTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/baba/callvault/calls/CallStateRepository.kt app/src/test/java/com/baba/callvault/calls/CallStateRepositoryTest.kt
git commit -m "feat(calls): CallStateRepository for shared current-call UI state"
```

---

## Task 7: `CallVaultInCallService` + `TelecomCallEventSource` + manifest

Android-framework glue: receives Telecom `Call`s, maps states → `CallEvent`s (via the router as
`TELECOM` source) and updates `CallStateRepository`, and launches `InCallActivity`. Not JVM-unit
tested; verified on-device in Task 10.

**Files:**
- Create: `app/src/main/java/com/baba/callvault/dialer/TelecomCallEventSource.kt`
- Create: `app/src/main/java/com/baba/callvault/dialer/CallVaultInCallService.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `CallDetection.router`, `DetectionMode.TELECOM`, `CallEvent`, `CallStateRepository`.
- Produces: a registered default-dialer `InCallService`.

- [ ] **Step 1: Implement** `TelecomCallEventSource.kt` (pure mapping helpers kept here so they *could* be unit-tested later):

```kotlin
package com.baba.callvault.dialer

import android.telecom.Call
import com.baba.callvault.calls.CallDirection
import com.baba.callvault.calls.CallEvent
import com.baba.callvault.calls.CallEventRouter
import com.baba.callvault.calls.DetectionMode

object TelecomCallEventSource {
    fun phaseOf(state: Int): CallEvent.Phase = when (state) {
        Call.STATE_RINGING -> CallEvent.Phase.RINGING
        Call.STATE_DIALING, Call.STATE_CONNECTING -> CallEvent.Phase.DIALING
        Call.STATE_ACTIVE -> CallEvent.Phase.ACTIVE
        Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> CallEvent.Phase.ENDED
        else -> CallEvent.Phase.ACTIVE
    }

    fun directionOf(details: Call.Details): CallDirection = when (details.callDirection) {
        Call.Details.DIRECTION_INCOMING -> CallDirection.INCOMING
        Call.Details.DIRECTION_OUTGOING -> CallDirection.OUTGOING
        else -> CallDirection.UNKNOWN
    }

    fun numberOf(details: Call.Details): String? =
        details.handle?.schemeSpecificPart

    fun submit(router: CallEventRouter, call: Call) {
        val d = call.details
        router.submit(
            DetectionMode.TELECOM,
            CallEvent(phaseOf(call.state), numberOf(d), directionOf(d),
                isEmergency = (d.callProperties and Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE) != 0),
        )
    }
}
```

- [ ] **Step 2: Implement** `CallVaultInCallService.kt`:

```kotlin
package com.baba.callvault.dialer

import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import com.baba.callvault.calls.CallDetection
import com.baba.callvault.calls.CallStateRepository
import com.baba.callvault.calls.UiCall
import com.baba.callvault.ui.dialer.InCallActivity

class CallVaultInCallService : InCallService() {

    private val callbacks = mutableMapOf<Call, Call.Callback>()

    override fun onCallAdded(call: Call) {
        val cb = object : Call.Callback() {
            override fun onStateChanged(c: Call, state: Int) = publish(c)
        }
        callbacks[call] = cb
        call.registerCallback(cb)
        publish(call)
        startActivity(Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onCallRemoved(call: Call) {
        callbacks.remove(call)?.let { call.unregisterCallback(it) }
        TelecomCallEventSource.submit(CallDetection.router, call) // ENDED
        CallStateRepository.update(null)
    }

    private fun publish(call: Call) {
        TelecomCallEventSource.submit(CallDetection.router, call)
        val d = call.details
        CallStateRepository.update(
            UiCall(
                number = TelecomCallEventSource.numberOf(d),
                phase = TelecomCallEventSource.phaseOf(call.state),
                direction = TelecomCallEventSource.directionOf(d),
                isEmergency = (d.callProperties and Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE) != 0,
                isRecording = CallStateRepository.current.value?.isRecording ?: false,
            )
        )
    }
}
```

- [ ] **Step 3: Register in `AndroidManifest.xml`** (inside `<application>`):

```xml
        <service
            android:name=".dialer.CallVaultInCallService"
            android:exported="true"
            android:permission="android.permission.BIND_INCALL_SERVICE"
            android:foregroundServiceType="specialUse">
            <meta-data android:name="android.telecom.IN_CALL_SERVICE_UI" android:value="true" />
            <meta-data android:name="android.telecom.IN_CALL_SERVICE_RINGING" android:value="true" />
            <intent-filter>
                <action android:name="android.telecom.InCallService" />
            </intent-filter>
        </service>
```
And add permissions near the existing `<uses-permission>` block:

```xml
    <uses-permission android:name="android.permission.MANAGE_OWN_CALLS" />
    <uses-permission android:name="android.permission.CALL_PHONE" />
    <uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />
```
(Note: `CALL_PHONE`/`READ_CALL_LOG` are also implicitly granted while the role is held; declaring `CALL_PHONE` lets the dialpad place calls.)

- [ ] **Step 4: Build** (UI class `InCallActivity` referenced here is created in Task 8 — if executing strictly in order, create an empty `InCallActivity` stub first, or reorder so Task 8 precedes this build). 

Run: `JAVA_HOME=... ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (after Task 8 stub exists).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/baba/callvault/dialer/TelecomCallEventSource.kt app/src/main/java/com/baba/callvault/dialer/CallVaultInCallService.kt app/src/main/AndroidManifest.xml
git commit -m "feat(dialer): InCallService + Telecom→CallEvent mapping + manifest registration"
```

---

## Task 8: In-call UI (`InCallActivity`, `InCallScreen`, `InCallViewModel`)

**Files:**
- Create: `app/src/main/java/com/baba/callvault/ui/dialer/InCallViewModel.kt`
- Create: `app/src/main/java/com/baba/callvault/ui/dialer/InCallScreen.kt`
- Create: `app/src/main/java/com/baba/callvault/ui/dialer/InCallActivity.kt`
- Create: `app/src/main/res/values/strings_dialer.xml` + 9 locale copies
- Test: `app/src/test/java/com/baba/callvault/ui/dialer/InCallViewModelTest.kt`

**Interfaces:**
- Consumes: `CallStateRepository.current` (Task 6), `InCallService` controls.
- Produces: a full-screen calling Activity bound to current-call state.

- [ ] **Step 1: Add strings** to `app/src/main/res/values/strings_dialer.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="dialer_incoming_title">Incoming call</string>
    <string name="dialer_answer">Answer</string>
    <string name="dialer_reject">Reject</string>
    <string name="dialer_end_call">End call</string>
    <string name="dialer_mute">Mute</string>
    <string name="dialer_speaker">Speaker</string>
    <string name="dialer_hold">Hold</string>
    <string name="dialer_keypad">Keypad</string>
    <string name="dialer_record">Record</string>
    <string name="dialer_stop_recording">Stop recording</string>
    <string name="dialer_unknown_caller">Unknown</string>
</resources>
```

- [ ] **Step 2: Translate** — create `strings_dialer.xml` in each of `values-fr, values-de, values-es, values-it, values-hu, values-pl, values-ru, values-vi, values-zh-rCN` with the same keys translated (reuse the translation approach from the i18n work: natural text, escape downstream). This is REQUIRED or `lintDebug` fails.

- [ ] **Step 3: Write the failing ViewModel test** (logic the VM exposes — label for a call):

```kotlin
package com.baba.callvault.ui.dialer

import com.baba.callvault.calls.CallDirection
import com.baba.callvault.calls.CallEvent
import com.baba.callvault.calls.UiCall
import org.junit.Assert.assertEquals
import org.junit.Test

class InCallViewModelTest {
    @Test fun shows_unknown_when_number_null() {
        assertEquals("Unknown", InCallLabels.displayNumber(UiCall(null, CallEvent.Phase.RINGING, CallDirection.INCOMING, false, false), unknown = "Unknown"))
    }
    @Test fun shows_number_when_present() {
        assertEquals("+15551234", InCallLabels.displayNumber(UiCall("+15551234", CallEvent.Phase.ACTIVE, CallDirection.OUTGOING, false, false), unknown = "Unknown"))
    }
}
```

- [ ] **Step 4: Run to verify it fails**

Run: `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.baba.callvault.ui.dialer.InCallViewModelTest"`
Expected: FAIL.

- [ ] **Step 5: Implement** `InCallViewModel.kt` (include the pure `InCallLabels` helper + VM that reads `CallStateRepository` and exposes call actions via the bound `InCallService`):

```kotlin
package com.baba.callvault.ui.dialer

import com.baba.callvault.calls.UiCall

object InCallLabels {
    fun displayNumber(call: UiCall?, unknown: String): String =
        call?.number?.takeIf { it.isNotBlank() } ?: unknown
}
```
(Plus a `ViewModel` exposing `CallStateRepository.current` and action lambdas — answer/reject/end/mute/speaker/hold/toggleRecord — delegating to the active `Call` held by the service. Wire recording toggle to the same `RecordingForegroundService` action the auto path uses.)

- [ ] **Step 6: Implement** `InCallScreen.kt` (Compose: render incoming vs active states from `current`, buttons using the string resources) and `InCallActivity.kt` (a `ComponentActivity` that sets `setShowWhenLocked(true)`, `setTurnScreenOn(true)`, hosts `InCallScreen` in `CallVaultTheme`). Keep these focused; no business logic.

- [ ] **Step 7: Run unit test + build + lint**

Run: `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.baba.callvault.ui.dialer.InCallViewModelTest" :app:lintDebug`
Expected: PASS + lint clean (translations present).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/baba/callvault/ui/dialer/ app/src/main/res/values*/strings_dialer.xml app/src/test/java/com/baba/callvault/ui/dialer/InCallViewModelTest.kt
git commit -m "feat(dialer): in-call UI (activity/screen/viewmodel) + localized strings"
```

---

## Task 9: Minimal dialpad + place call (incl. emergency)

**Files:**
- Create: `app/src/main/java/com/baba/callvault/ui/dialer/DialpadScreen.kt`
- Create: `app/src/main/java/com/baba/callvault/dialer/CallPlacer.kt`
- Test: `app/src/test/java/com/baba/callvault/dialer/CallPlacerTest.kt`
- Modify: `app/src/main/java/com/baba/callvault/AppNavigationScreen.kt` (expose dialpad entry in dialer mode), `ui/navigation/AppScreen.kt` (add `Dialer`)

**Interfaces:**
- Produces: `class CallPlacer(context)` with `fun place(number: String)` using `TelecomManager.placeCall`; emergency numbers pass straight through.

- [ ] **Step 1: Write the failing test** (pure validation: which input is dialable):

```kotlin
package com.baba.callvault.dialer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallPlacerTest {
    @Test fun blank_is_not_dialable() { assertFalse(CallPlacer.isDialable("")) }
    @Test fun digits_are_dialable() { assertTrue(CallPlacer.isDialable("112")) }
    @Test fun sanitizes_display_to_dial_string() {
        assertEquals("+15551234", CallPlacer.normalize(" +1 (555) 1234 "))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.baba.callvault.dialer.CallPlacerTest"`
Expected: FAIL.

- [ ] **Step 3: Implement** `CallPlacer.kt`:

```kotlin
package com.baba.callvault.dialer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat

class CallPlacer(private val context: Context) {
    fun place(number: String) {
        if (!isDialable(number)) return
        val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val uri = Uri.fromParts("tel", normalize(number), null)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED) {
            tm.placeCall(uri, null) // emergency numbers handled by the platform
        }
    }
    companion object {
        fun isDialable(input: String): Boolean = normalize(input).isNotEmpty()
        fun normalize(input: String): String =
            input.trim().filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.baba.callvault.dialer.CallPlacerTest"`
Expected: PASS.

- [ ] **Step 5: Implement** `DialpadScreen.kt` (Compose: 0–9/*/# grid, display field, backspace, call button calling `CallPlacer.place`). Add `AppScreen.Dialer` and expose a dialpad entry point on Home **only when** `isDialerModeEnabled()` is true.

- [ ] **Step 6: Build + lint**

Run: `JAVA_HOME=... ./gradlew :app:testDebugUnitTest :app:lintDebug`
Expected: PASS + clean.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/baba/callvault/ui/dialer/DialpadScreen.kt app/src/main/java/com/baba/callvault/dialer/CallPlacer.kt app/src/main/java/com/baba/callvault/ui/navigation/AppScreen.kt app/src/main/java/com/baba/callvault/AppNavigationScreen.kt app/src/test/java/com/baba/callvault/dialer/CallPlacerTest.kt
git commit -m "feat(dialer): minimal dialpad + CallPlacer (emergency-safe)"
```

---

## Task 10: Settings toggle + role flow + fallback banner

**Files:**
- Modify: `app/src/main/java/com/baba/callvault/ui/viewmodels/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/baba/callvault/ui/screens/SettingsScreen.kt`
- Modify: `app/src/main/res/values/strings_dialer.xml` + 9 locales (toggle labels, banner)
- Test: `app/src/test/java/com/baba/callvault/dialer/DialerModeStateTest.kt`

**Interfaces:**
- Consumes: `DialerRoleController` (Task 4), `AppPreferences` (Task 3), `CallDetection.setMode` (Task 5).
- Produces: reconciled mode state (pref ↔ real role).

- [ ] **Step 1: Write the failing test** — the reconcile rule: effective dialer mode is true only if pref ON **and** role held:

```kotlin
package com.baba.callvault.dialer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialerModeStateTest {
    @Test fun effective_requires_pref_and_role() {
        assertTrue(DialerModeState.effective(prefOn = true, roleHeld = true))
        assertFalse(DialerModeState.effective(prefOn = true, roleHeld = false))
        assertFalse(DialerModeState.effective(prefOn = false, roleHeld = true))
    }
    @Test fun banner_shown_when_pref_on_but_role_lost() {
        assertTrue(DialerModeState.shouldShowRoleLostBanner(prefOn = true, roleHeld = false))
        assertFalse(DialerModeState.shouldShowRoleLostBanner(prefOn = false, roleHeld = false))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.baba.callvault.dialer.DialerModeStateTest"`
Expected: FAIL.

- [ ] **Step 3: Implement** `DialerModeState` (put alongside `DialerRoleController`):

```kotlin
package com.baba.callvault.dialer

object DialerModeState {
    fun effective(prefOn: Boolean, roleHeld: Boolean): Boolean = prefOn && roleHeld
    fun shouldShowRoleLostBanner(prefOn: Boolean, roleHeld: Boolean): Boolean = prefOn && !roleHeld
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.baba.callvault.dialer.DialerModeStateTest"`
Expected: PASS.

- [ ] **Step 5: Wire Settings UI** — add a "Dialer mode" switch. On enable: launch `DialerRoleController.requestRoleIntent()` via an `ActivityResultLauncher`; on success persist `setDialerModeEnabled(true)` and call `CallDetection.setMode(true)`; on denial revert the switch. On disable: `setDialerModeEnabled(false)`, `CallDetection.setMode(false)`, show `releaseRoleHint()` guidance. On screen resume, recompute `DialerModeState` from `controller.isDefaultDialer()`; if `shouldShowRoleLostBanner`, show a banner. Add the toggle/banner strings to `strings_dialer.xml` + all 9 locales.

- [ ] **Step 6: Build + lint + unit tests**

Run: `JAVA_HOME=... ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
Expected: all green.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/baba/callvault/ui/ app/src/main/java/com/baba/callvault/dialer/DialerModeState.kt app/src/main/res/values*/strings_dialer.xml app/src/test/java/com/baba/callvault/dialer/DialerModeStateTest.kt
git commit -m "feat(dialer): Settings toggle, role request flow, role-loss fallback banner"
```

---

## Task 11: End-to-end on-device verification

**Files:** none (verification only).

- [ ] **Step 1: Install**

Run: `JAVA_HOME=... ./gradlew :app:installDebug` (or `adb -s 6011b07e install -r app/build/outputs/apk/debug/app-debug.apk`).

- [ ] **Step 2: Enable dialer mode** in Settings → accept the "make CallVault your default phone app" system prompt. Verify the switch reflects `isDefaultDialer() == true`.

- [ ] **Step 3: Outgoing call** via the dialpad → in-call screen appears; recording starts per auto-record settings; recording lands in the catalog with correct number/direction.

- [ ] **Step 4: Incoming call** → full-screen incoming UI over lock screen; answer/reject work; recording behaves per settings.

- [ ] **Step 5: Emergency check (no real call):** type an emergency number on the dialpad and confirm the platform emergency UI takes over (do NOT place it); confirm it is never filtered or record-gated in code paths.

- [ ] **Step 6: Role loss** → change default phone app away in Android settings → return to CallVault → banner shows, detection falls back to broadcast, recording-only still works.

- [ ] **Step 7: Disable dialer mode** in Settings → role released/guided, app returns to pure recording-only behavior identical to v1.2.1.

- [ ] **Step 8: Regression** → with dialer mode OFF, confirm incoming + outgoing auto-record exactly as before.

- [ ] **Step 9: Commit** any fixes found; otherwise tag the working state.

```bash
git commit --allow-empty -m "test(dialer): Phase 1 on-device verification complete"
```

---

## Self-Review Notes

- **Spec coverage:** mode infra (T3,T10) · role mgmt (T4,T10) · InCallService (T7) · incoming/in-call screen (T8) · minimal dialpad + emergency (T9) · Telecom detection feeding unchanged pipeline (T1,T2,T5,T7) · reversible toggle + fallback (T10) · recording-only regression (T5,T11) · testing (all). All Section 1–8 spec items mapped.
- **Translation gate:** new strings introduced in T8/T10 include the all-locale step (constraint honored).
- **Ordering caveat:** T7 references `InCallActivity` (created in T8). When executing strictly in order, create an empty `InCallActivity` stub in T7 Step 4 or build T8 before T7's build step. Flagged in T7 Step 4.
- **Type consistency:** `CallEvent.Phase`, `CallDirection`, `UiCall`, `DetectionMode`, `CallDetection.router`, `CallSessionManager.onCallEvent` used consistently across tasks.
