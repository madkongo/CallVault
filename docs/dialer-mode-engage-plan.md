# Dialer Mode "Engage + Reachable" (A+B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make dialer mode actually engage on locked-down OEMs by forcing CallVault as the Telecom default dialer over the existing ADB/shell channel, and add a toggle-linked "CallVault Dialer" launcher icon.

**Architecture:** A `DialerDefaultEnforcer` runs `cmd telecom set-default-dialer` + `pm grant CALL_PHONE` via the existing `AdbShell`, storing/restoring the prior default for clean relinquish. `SettingsViewModel.setDialerModeEnabled` orchestrates enforce/relinquish + a `PackageManager`-toggled `activity-alias` launcher icon. Re-assert on enable/boot/resume; the existing role-lost banner re-asserts on tap.

**Tech Stack:** Kotlin, Android (minSdk 30/targetSdk 36), `TelecomManager`, `RoleManager`, the app's `AdbShell` (embedded ADB), Jetpack Compose. Tests: JUnit4 + MockK + Robolectric (`@Config(sdk=[35])` for Robolectric tests).

## Global Constraints

- minSdk 30, targetSdk 36; guard APIs accordingly.
- Recording-only mode (dialer pref OFF) stays byte-for-byte identical to v1.2.1.
- `isDefaultDialer()` truth = `TelecomManager.getDefaultDialerPackage() == packageName` (already implemented).
- All ADB/shell commands run **off the main thread** (suspend / `Dispatchers.IO`).
- New user-facing strings MUST be added to `values/` AND all 9 locales (fr,de,es,it,hu,pl,ru,vi,zh-rCN) or `lintDebug` fails.
- Emergency calls always use the preloaded dialer (AOSP) — never gate/block them.
- Robolectric 4.14 caps at SDK 35 → annotate Robolectric tests `@Config(sdk = [35])`.
- Build env: `JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home`; run on device serial `6011b07e`.
- Branch: `feat/dialer-mode-phase1`. Conventional commits; commit per task.

---

## File Structure

- **New:** `dialer/DialerDefaultEnforcer.kt` — force/relinquish Telecom default via AdbShell (+ pure command strings).
- **New:** `dialer/DialerLauncherIcon.kt` — enable/disable the launcher `activity-alias`.
- **Modify:** `integrations/adb/AdbShell.kt` — add generic `runShellCommand`.
- **Modify:** `data/AppPreferences.kt` — `PRIOR_DEFAULT_DIALER` string pref.
- **Modify:** `AndroidManifest.xml` — `activity-alias` (disabled by default) + routing extra on MainActivity.
- **Modify:** `MainActivity.kt` — route the launcher-alias intent to the dialpad (`AppScreen.Dialer`).
- **Modify:** `ui/viewmodels/SettingsViewModel.kt` — orchestrate enforce/relinquish + icon toggle.
- **Modify:** `ui/screens/SettingsScreen.kt` — role-lost banner + toggle call the enforcer.
- **Modify:** `services/boot/AdbConnectionService.kt` — re-assert on boot when pref on.
- **Modify:** `AppNavigationScreen.kt` — re-assert on ON_RESUME when pref on.
- **New strings:** `res/values/strings_dialer.xml` (+ 9 locales): `dialer_launcher_label`.

---

## Task 1: `AdbShell.runShellCommand` (generic command runner)

**Files:**
- Modify: `app/src/main/java/com/baba/callvault/integrations/adb/AdbShell.kt`

**Interfaces:**
- Produces: `fun AdbShell.runShellCommand(context: Context, command: String): Boolean` — runs a shell command over ADB, drains output, returns true on success (no exception), false otherwise.

- [ ] **Step 1: Implement** — add to `AdbShell` (model on the existing `grantSecureSettingsIfNeeded` pattern, lines ~158-167):

```kotlin
    /**
     * Runs an arbitrary shell command over the embedded ADB connection (uid 2000 shell), draining its
     * output so the command completes. Returns true if it ran without throwing. Caller must be OFF the
     * main thread. Used for privileged dialer setup (cmd telecom set-default-dialer, pm grant CALL_PHONE).
     */
    fun runShellCommand(context: Context, command: String): Boolean =
        runCatching {
            openShell(context, command).use { s -> s.openInputStream().use { it.readBytes() } }
            AppLogger.i(TAG, "ADB shell command ran: $command")
            true
        }.onFailure { AppLogger.w(TAG, "ADB shell command failed ($command): ${it.message}") }
            .getOrDefault(false)
```

- [ ] **Step 2: Build**

Run: `JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/baba/callvault/integrations/adb/AdbShell.kt
git commit -m "feat(adb): generic AdbShell.runShellCommand for privileged setup commands"
```

---

## Task 2: `PRIOR_DEFAULT_DIALER` preference

**Files:**
- Modify: `app/src/main/java/com/baba/callvault/data/AppPreferences.kt`
- Test: `app/src/test/java/com/baba/callvault/data/PriorDefaultDialerPrefTest.kt`

**Interfaces:**
- Produces: `fun getPriorDefaultDialer(): String?` (default null), `fun setPriorDefaultDialer(pkg: String?)` (null clears the key).

- [ ] **Step 1: Inspect** the existing `Key` enum + a String accessor pair in `AppPreferences.kt` (e.g. `getRecordingFolderUri`/setter) and mirror the style.

- [ ] **Step 2: Write the failing test** at `PriorDefaultDialerPrefTest.kt`:

```kotlin
package com.baba.callvault.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PriorDefaultDialerPrefTest {
    private val prefs = AppPreferences(ApplicationProvider.getApplicationContext())

    @Test fun defaults_to_null() { assertNull(prefs.getPriorDefaultDialer()) }

    @Test fun stores_and_clears() {
        prefs.setPriorDefaultDialer("com.google.android.dialer")
        assertEquals("com.google.android.dialer", prefs.getPriorDefaultDialer())
        prefs.setPriorDefaultDialer(null)
        assertNull(prefs.getPriorDefaultDialer())
    }
}
```

- [ ] **Step 3: Run — verify it fails**

Run: `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.baba.callvault.data.PriorDefaultDialerPrefTest"`
Expected: FAIL (unresolved `getPriorDefaultDialer`).

- [ ] **Step 4: Implement** — add a `PRIOR_DEFAULT_DIALER("prior_default_dialer")` key and accessors (adapt field names to the real file):

```kotlin
    fun getPriorDefaultDialer(): String? =
        sharedPreferences.getString(Key.PRIOR_DEFAULT_DIALER.id, null)

    fun setPriorDefaultDialer(pkg: String?) {
        sharedPreferences.edit().apply {
            if (pkg == null) remove(Key.PRIOR_DEFAULT_DIALER.id) else putString(Key.PRIOR_DEFAULT_DIALER.id, pkg)
        }.apply()
    }
```

- [ ] **Step 5: Run — verify it passes**

Run: `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.baba.callvault.data.PriorDefaultDialerPrefTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/baba/callvault/data/AppPreferences.kt app/src/test/java/com/baba/callvault/data/PriorDefaultDialerPrefTest.kt
git commit -m "feat(prefs): PRIOR_DEFAULT_DIALER for clean dialer-mode relinquish"
```

---

## Task 3: `DialerDefaultEnforcer`

**Files:**
- Create: `app/src/main/java/com/baba/callvault/dialer/DialerDefaultEnforcer.kt`
- Test: `app/src/test/java/com/baba/callvault/dialer/DialerDefaultEnforcerTest.kt`

**Interfaces:**
- Consumes: `AdbShell.runShellCommand` (Task 1), `AppPreferences.get/setPriorDefaultDialer` (Task 2), `DialerRoleController.isDefaultDialer()`, `TelecomManager.getDefaultDialerPackage()`.
- Produces:
  - pure: `object DialerCommands { fun setDefault(pkg: String): String; fun grantCallPhone(pkg: String): String; fun restore(priorPkg: String): String }`
  - `class DialerDefaultEnforcer(context, prefs, roleController)` with `fun enforce(): Boolean` and `fun relinquish()` (both blocking; callers dispatch to IO).

- [ ] **Step 1: Write the failing test** (pure command strings) at `DialerDefaultEnforcerTest.kt`:

```kotlin
package com.baba.callvault.dialer

import org.junit.Assert.assertEquals
import org.junit.Test

class DialerDefaultEnforcerTest {
    @Test fun set_default_command() {
        assertEquals("cmd telecom set-default-dialer com.baba.callvault",
            DialerCommands.setDefault("com.baba.callvault"))
    }
    @Test fun grant_call_phone_command() {
        assertEquals("pm grant com.baba.callvault android.permission.CALL_PHONE",
            DialerCommands.grantCallPhone("com.baba.callvault"))
    }
    @Test fun restore_command() {
        assertEquals("cmd telecom set-default-dialer com.google.android.dialer",
            DialerCommands.restore("com.google.android.dialer"))
    }
}
```

- [ ] **Step 2: Run — verify it fails**

Run: `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.baba.callvault.dialer.DialerDefaultEnforcerTest"`
Expected: FAIL (unresolved `DialerCommands`).

- [ ] **Step 3: Implement** `DialerDefaultEnforcer.kt`:

```kotlin
package com.baba.callvault.dialer

import android.content.Context
import android.telecom.TelecomManager
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.integrations.adb.AdbShell
import com.baba.callvault.utils.AppLogger

/** Pure shell-command strings (unit-testable). */
object DialerCommands {
    fun setDefault(pkg: String) = "cmd telecom set-default-dialer $pkg"
    fun grantCallPhone(pkg: String) = "pm grant $pkg android.permission.CALL_PHONE"
    fun restore(priorPkg: String) = "cmd telecom set-default-dialer $priorPkg"
}

/**
 * Forces CallVault as the Telecom default dialer via the embedded ADB shell, because OEMs (OxygenOS)
 * grant ROLE_DIALER but don't propagate it into Telecom's default-dialer cache. Blocking; call OFF the
 * main thread.
 */
class DialerDefaultEnforcer(
    private val context: Context,
    private val prefs: AppPreferences,
    private val roleController: DialerRoleController,
) {
    private val pkg = context.packageName
    private val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

    /** Make CallVault the Telecom default dialer + grant CALL_PHONE. Returns true if it actually became default. */
    fun enforce(): Boolean {
        if (roleController.isDefaultDialer()) return true
        // Remember who held it so we can restore on relinquish (only if it wasn't already us).
        @Suppress("DEPRECATION")
        val current = telecom?.defaultDialerPackage
        if (current != null && current != pkg && prefs.getPriorDefaultDialer() == null) {
            prefs.setPriorDefaultDialer(current)
        }
        AdbShell.runShellCommand(context, DialerCommands.setDefault(pkg))
        AdbShell.runShellCommand(context, DialerCommands.grantCallPhone(pkg))
        val ok = roleController.isDefaultDialer()
        AppLogger.i(TAG, "enforce() default-dialer now=${'$'}{if (ok) pkg else current} ok=$ok")
        return ok
    }

    /** Restore the previously-stored default dialer (best-effort) and clear the stored value. */
    fun relinquish() {
        val prior = prefs.getPriorDefaultDialer()
            ?: @Suppress("DEPRECATION") telecom?.systemDialerPackage
        if (prior != null && prior != pkg) {
            AdbShell.runShellCommand(context, DialerCommands.restore(prior))
        }
        prefs.setPriorDefaultDialer(null)
        AppLogger.i(TAG, "relinquish() restored default dialer to ${'$'}prior")
    }

    private companion object { const val TAG = "CV:DialerEnforcer" }
}
```

- [ ] **Step 4: Run — verify it passes**

Run: `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.baba.callvault.dialer.DialerDefaultEnforcerTest" :app:assembleDebug`
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/baba/callvault/dialer/DialerDefaultEnforcer.kt app/src/test/java/com/baba/callvault/dialer/DialerDefaultEnforcerTest.kt
git commit -m "feat(dialer): DialerDefaultEnforcer forces/relinquishes Telecom default via ADB"
```

---

## Task 4: Launcher `activity-alias` + `DialerLauncherIcon` + routing

**Files:**
- Create: `app/src/main/java/com/baba/callvault/dialer/DialerLauncherIcon.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/baba/callvault/MainActivity.kt`
- Modify: `app/src/main/res/values/strings_dialer.xml` (+ 9 locales)

**Interfaces:**
- Produces: `object DialerLauncherIcon { fun setEnabled(context: Context, enabled: Boolean) }`.
- The alias routes to the dialpad: MainActivity reads intent extra `open_dialer=true` and navigates to `AppScreen.Dialer`.

- [ ] **Step 1: Add the alias** to `AndroidManifest.xml` (inside `<application>`, after the MainActivity `</activity>`):

```xml
        <!-- Toggle-able launcher icon for the in-app dialpad; enabled only while dialer mode is on. -->
        <activity-alias
            android:name=".dialer.DialerLauncherAlias"
            android:targetActivity=".MainActivity"
            android:enabled="false"
            android:exported="true"
            android:label="@string/dialer_launcher_label"
            android:icon="@mipmap/ic_launcher">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <meta-data android:name="cv.open_dialer" android:value="true" />
        </activity-alias>
```

- [ ] **Step 2: Add strings** — `res/values/strings_dialer.xml` add `<string name="dialer_launcher_label">CallVault Dialer</string>`, and add the SAME key, translated, to all 9 `values-*/strings_dialer.xml` (fr,de,es,it,hu,pl,ru,vi,zh-rCN). Keep "CallVault" verbatim. Required or `lintDebug` fails.

- [ ] **Step 3: Implement** `DialerLauncherIcon.kt`:

```kotlin
package com.baba.callvault.dialer

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Shows/hides the "CallVault Dialer" launcher icon (an activity-alias) with dialer mode. */
object DialerLauncherIcon {
    fun setEnabled(context: Context, enabled: Boolean) {
        val alias = ComponentName(context.packageName, "com.baba.callvault.dialer.DialerLauncherAlias")
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        context.packageManager.setComponentEnabledSetting(alias, state, PackageManager.DONT_KILL_APP)
    }
}
```

- [ ] **Step 4: Route the alias to the dialpad** in `MainActivity.kt` — when launched via the alias, the intent's component is the alias; detect it and signal the nav layer to open the dialpad. Read the existing nav entry (`AppNavigationViewModel.navigateTo(AppScreen.Dialer)`). In `MainActivity.onCreate`/`onNewIntent`, after the content is set, if `intent?.component?.className == "com.baba.callvault.dialer.DialerLauncherAlias"` (or the alias was the launching component), request the dialpad route. Concretely, set a flag the composable reads once:

```kotlin
        val openDialer = intent?.component?.shortClassName?.endsWith("DialerLauncherAlias") == true
        // Pass openDialer into AppNavigationScreen(...) so it calls appNavViewModel.navigateTo(AppScreen.Dialer)
        // once on first composition when true (only meaningful if dialer mode is on).
```
Wire `AppNavigationScreen(openDialer = openDialer)`; in it, `LaunchedEffect(openDialer) { if (openDialer) appNavViewModel.navigateTo(AppScreen.Dialer) }`. (Match the actual `AppNavigationScreen` signature/params.)

- [ ] **Step 5: Build + lint**

Run: `JAVA_HOME=... ./gradlew :app:assembleDebug :app:lintDebug`
Expected: BUILD SUCCESSFUL + lint clean (all 10 strings_dialer.xml carry `dialer_launcher_label`).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/baba/callvault/dialer/DialerLauncherIcon.kt app/src/main/AndroidManifest.xml app/src/main/java/com/baba/callvault/MainActivity.kt app/src/main/java/com/baba/callvault/AppNavigationScreen.kt app/src/main/res/values*/strings_dialer.xml
git commit -m "feat(dialer): toggle-able CallVault Dialer launcher icon routing to the dialpad"
```

---

## Task 5: Orchestrate in `SettingsViewModel.setDialerModeEnabled`

**Files:**
- Modify: `app/src/main/java/com/baba/callvault/ui/viewmodels/SettingsViewModel.kt`

**Interfaces:**
- Consumes: `DialerDefaultEnforcer` (Task 3), `DialerLauncherIcon` (Task 4), `CallDetection.setMode`, `DialerModeState.effective`, existing `dialerRoleController`.

- [ ] **Step 1: Inspect** the current `setDialerModeEnabled` (around line 373) and the VM's coroutine scope (`viewModelScope`) + `appContext`.

- [ ] **Step 2: Implement** — replace the body so enable enforces + shows the icon, disable relinquishes + hides it; ADB work on `Dispatchers.IO`:

```kotlin
    private val dialerEnforcer by lazy {
        com.baba.callvault.dialer.DialerDefaultEnforcer(appContext, preferences, dialerRoleController)
    }

    override fun setDialerModeEnabled(enabled: Boolean) {
        preferences.setDialerModeEnabled(enabled)
        com.baba.callvault.dialer.DialerLauncherIcon.setEnabled(appContext, enabled)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (enabled) dialerEnforcer.enforce() else dialerEnforcer.relinquish()
            if (com.baba.callvault.calls.CallDetection.isInitialized) {
                com.baba.callvault.calls.CallDetection.setMode(
                    com.baba.callvault.dialer.DialerModeState.effective(
                        prefOn = enabled,
                        roleHeld = dialerRoleController.isDefaultDialer(),
                    )
                )
            }
            refresh() // re-read state so the toggle/banner reflect reality
        }
    }
```
(Use the existing `refresh()` and imports already present; add `viewModelScope`/`launch`/`Dispatchers` imports if missing.)

- [ ] **Step 3: Build**

Run: `JAVA_HOME=... ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL + existing tests pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/baba/callvault/ui/viewmodels/SettingsViewModel.kt
git commit -m "feat(dialer): enable=enforce+show icon, disable=relinquish+hide icon"
```

---

## Task 6: Re-assert on boot, resume, and banner tap

**Files:**
- Modify: `app/src/main/java/com/baba/callvault/services/boot/AdbConnectionService.kt`
- Modify: `app/src/main/java/com/baba/callvault/AppNavigationScreen.kt`
- Modify: `app/src/main/java/com/baba/callvault/ui/screens/SettingsScreen.kt`

**Interfaces:**
- Consumes: `DialerDefaultEnforcer.enforce()`, `AppPreferences.isDialerModeEnabled()`, `DialerRoleController.isDefaultDialer()`.

- [ ] **Step 1: Boot re-assert** — in `AdbConnectionService.kt`, at the point the log prints "Boot: recorder daemon connected=true" (daemon connected), add (off main thread, where it already runs background work):

```kotlin
        val prefs = com.baba.callvault.data.AppPreferences(this)
        if (prefs.isDialerModeEnabled()) {
            val rc = com.baba.callvault.dialer.DialerRoleController(this)
            if (!rc.isDefaultDialer()) {
                com.baba.callvault.dialer.DialerDefaultEnforcer(this, prefs, rc).enforce()
            }
        }
```

- [ ] **Step 2: Resume re-assert** — in `AppNavigationScreen.kt`'s existing `LifecycleEventObserver` ON_RESUME branch (where it already calls `appNavViewModel.refresh()`), add a background re-assert:

```kotlin
            if (event == Lifecycle.Event.ON_RESUME) {
                appNavViewModel.refresh()
                settingsViewModel.refresh()
                settingsViewModel.reassertDialerDefaultIfNeeded()  // new VM method (below)
            }
```
And add to `SettingsViewModel`:
```kotlin
    fun reassertDialerDefaultIfNeeded() {
        if (!preferences.isDialerModeEnabled()) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (!dialerRoleController.isDefaultDialer()) dialerEnforcer.enforce()
            refresh()
        }
    }
```

- [ ] **Step 3: Banner tap re-asserts** — in `SettingsScreen.kt`, the role-lost banner `.clickable { ... }` (currently `dialerRoleController?.requestRoleIntent()`) should instead trigger the enforcer. Replace its body with a call into the VM:
```kotlin
                    .clickable { actions.reassertDialerDefault() }
```
Add `reassertDialerDefault()` to `SettingsActions`/VM (same as `reassertDialerDefaultIfNeeded` but unconditional enforce):
```kotlin
    fun reassertDialerDefault() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { dialerEnforcer.enforce(); refresh() }
    }
```
(Provide a no-op default in the preview/fake `SettingsActions` impl so previews compile.)

- [ ] **Step 4: Build + lint + tests**

Run: `JAVA_HOME=... ./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest`
Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/baba/callvault/services/boot/AdbConnectionService.kt app/src/main/java/com/baba/callvault/AppNavigationScreen.kt app/src/main/java/com/baba/callvault/ui/screens/SettingsScreen.kt app/src/main/java/com/baba/callvault/ui/viewmodels/SettingsViewModel.kt
git commit -m "feat(dialer): re-assert default dialer on boot, resume, and banner tap"
```

---

## Task 7: On-device verification (user-driven)

**Files:** none.

- [ ] **Step 1:** Install debug build (`adb -s 6011b07e install -r app/build/outputs/apk/debug/app-debug.apk`), re-grant `WRITE_SECURE_SETTINGS`.
- [ ] **Step 2:** Enable dialer mode → verify `cmd telecom get-default-dialer` == `com.baba.callvault`, `CALL_PHONE` granted, and a "CallVault Dialer" icon appears in the app drawer.
- [ ] **Step 3:** Open that icon → lands on the dialpad. Place a call → CallVault in-call UI + recording via Telecom (confirm in `dumpsys telecom` InCallService bound + a recording saved).
- [ ] **Step 4:** Disable dialer mode → icon disappears, `get-default-dialer` restored to the prior dialer, recording-only still works.
- [ ] **Step 5:** Reboot → re-asserted (pref still on). Open Google Phone to steal it, return to CallVault → resume re-asserts (or banner → tap re-asserts).
- [ ] **Step 6:** Regression: dialer mode OFF behaves exactly as v1.2.1.

---

## Self-Review Notes

- **Spec coverage:** enforce/relinquish (T3) · CALL_PHONE grant (T3) · prior-default capture/restore (T2,T3) · launcher icon toggle (T4) · orchestration (T5) · re-assert enable/boot/resume/banner (T5,T6) · recording-only unchanged (no detection code touched) · on-device verify (T7). All spec sections mapped.
- **Translation gate:** T4 adds `dialer_launcher_label` to all 10 locale files.
- **Type consistency:** `DialerCommands.setDefault/grantCallPhone/restore`, `DialerDefaultEnforcer.enforce()/relinquish()`, `DialerLauncherIcon.setEnabled`, `get/setPriorDefaultDialer`, `reassertDialerDefault()/reassertDialerDefaultIfNeeded()` used consistently across tasks.
- **Threading:** all ADB/shell calls dispatched to `Dispatchers.IO` (T5,T6) or already-background boot path (T6).
- **Ordering note:** T5/T6 reference `DialerDefaultEnforcer` (T3) and `DialerLauncherIcon` (T4) — implement T1–T4 before T5–T6.
