# CallVault Unified — Plan 3: Storage Routing (Local / Drive / Both)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After a call is recorded to the local working folder, automatically route it to the user's chosen destination — **Local only**, **Google Drive only**, or **Both** — with verify-before-delete and automatic retry. This is the feature the user originally asked for.

**Architecture:** Recording still always writes to the local SAF folder (the muxer needs a seekable local file — proven in Plan 2). After finalize, a **StorageRouter** enqueues an Android **WorkManager** job that copies the finished `.ogg` to the Drive SAF folder with a **write-only** stream (which cloud DocumentsProviders support — this is the fix for the original "Drive can't be a recording target" bug), verifies the copy by size, deletes the local file when the target is Drive-only, and retries with backoff (network-constrained) on failure.

**Tech Stack:** Kotlin + Compose · androidx.work (WorkManager) · SAF (DocumentFile / ContentResolver) · existing AppPreferences/SafHelper.

---

## Decomposition (Plan 3 of N)

- **Plan 1 (done):** embedded-ADB spike — GREEN.
- **Plan 2 (done):** records real calls over embedded ADB, no Shizuku — GREEN.
- **Plan 3 (this doc):** storage routing Local/Drive/Both (the original ask).
- **Plan 4 (later):** full Shizuku removal/cleanup (delete `ShellService`/`IShellService.aidl`/`ShizukuConnectionManager`/Shizuku deps+provider/`spike/` package/vestigial Shizuku prefs+settings) and hands-free boot (BootBridge/watchdog/whitelisting/QS-tile).

## Facts established (reuse — don't reinvent)

- Recording finalizes in `RecordingForegroundService.stopRecordingSessionAndService()`: after
  `activeSession.release()`, the final file is `activeSession.currentRecordingUri` (a SAF
  `content://` URI in the user's recording folder). An optional async rename (CallLog
  phone-number fallback) may follow.
- `SafHelper` already does SAF create/validate. Add a copy that uses a **write-only** output
  stream (Drive supports `"w"`, not `"rw"` — the muxer's `rw` need is why Drive can't be the
  *recording* target, but a post-hoc copy is fine).
- `AppPreferences` uses a `Key` enum + `DefaultsValue` + typed getters/setters (e.g.
  `getRecordingFolderUri`/`setRecordingFolderUri`, `getAudioSource`). Follow that exact pattern.
- The recording folder picker already exists (SAF `OpenDocumentTree` + `takePersistableUriPermission`)
  in `PermissionsScreen`/`SettingsScreen` — mirror it for the Drive folder.
- Google Drive provides a SAF DocumentsProvider when the Drive app is installed; the user picks a
  Drive folder via the same system tree picker. No Google sign-in / API keys (per the design spec).

## File Structure

**New:**
- `data/StorageTarget.kt` — `enum class StorageTarget { LOCAL, DRIVE, BOTH }` (+ `fromKey`).
- `system/storage/RecordingCopyWorker.kt` — WorkManager `CoroutineWorker`: copy local→Drive, verify, optional delete-local, retry.
- `system/storage/StorageRouter.kt` — decides per `StorageTarget` and enqueues the worker (no-op for LOCAL).

**Modified:**
- `data/AppPreferences.kt` — add `STORAGE_TARGET` + `DRIVE_FOLDER_URI` keys/getters/setters.
- `system/storage/SafHelper.kt` — add `copyFileToFolder(...)` (write-only) + `fileSize(uri)`.
- `services/recording/RecordingForegroundService.kt` — call `StorageRouter.route(...)` once the final file is settled.
- a settings screen (`ui/screens/SettingsScreen.kt`) — Storage Target selector + Drive folder picker.
- `app/build.gradle.kts` — add `androidx.work:work-runtime-ktx`.

---

## Task 1: Storage preferences

**Files:**
- Create: `data/StorageTarget.kt`
- Modify: `data/AppPreferences.kt`

- [ ] **Step 1:** Create `StorageTarget`:
```kotlin
package com.kitsumed.shizucallrecorder.data
enum class StorageTarget(val key: String) {
    LOCAL("local"), DRIVE("drive"), BOTH("both");
    companion object { fun fromKey(k: String) = entries.firstOrNull { it.key == k } ?: LOCAL }
}
```
- [ ] **Step 2:** In `AppPreferences`, following the existing `Key` enum + `DefaultsValue` + getter/setter pattern (mirror `AUDIO_SOURCE` / `getRecordingFolderUri`), add:
  - `DefaultsValue.STORAGE_TARGET = StorageTarget.LOCAL.key`
  - `Key.STORAGE_TARGET("storage_target")`, `Key.DRIVE_FOLDER_URI("drive_folder_uri")`
  - `fun getStorageTarget(): StorageTarget = StorageTarget.fromKey(getString(Key.STORAGE_TARGET, DefaultsValue.STORAGE_TARGET) ?: DefaultsValue.STORAGE_TARGET)`
  - `fun setStorageTarget(t: StorageTarget) = setString(Key.STORAGE_TARGET, t.key)`
  - `fun getDriveFolderUri(): Uri?` and `fun setDriveFolderUri(uri: Uri)` mirroring `getRecordingFolderUri`/`setRecordingFolderUri`.
- [ ] **Step 3:** Compile (`./gradlew :app:compileDebugKotlin`). Commit `feat(prefs): storage target + drive folder`.

## Task 2: SafHelper copy (write-only) + size

**Files:** Modify `system/storage/SafHelper.kt`

- [ ] **Step 1:** Add:
```kotlin
/** Copies [srcUri] into [destFolderUri] as [displayName] using a WRITE-ONLY stream (works on
 *  cloud providers like Drive). Returns the new file's Uri, or null on failure. */
fun copyFileToFolder(context: Context, srcUri: Uri, destFolderUri: Uri, displayName: String, mimeType: String): Uri? {
    val dir = DocumentFile.fromTreeUri(context, destFolderUri) ?: return null
    if (!dir.canWrite()) return null
    val dest = dir.createFile(mimeType, displayName) ?: return null
    return try {
        context.contentResolver.openInputStream(srcUri).use { input ->
            context.contentResolver.openOutputStream(dest.uri, "w").use { output ->   // "w" not "rw"
                if (input == null || output == null) return null
                input.copyTo(output)
            }
        }
        dest.uri
    } catch (e: Exception) { runCatching { dest.delete() }; null }
}

/** Returns the byte length of [uri], or -1 if unknown. */
fun fileSize(context: Context, uri: Uri): Long =
    runCatching { DocumentFile.fromSingleUri(context, uri)?.length() ?: -1L }.getOrDefault(-1L)
```
- [ ] **Step 2:** Compile. Commit `feat(saf): write-only copy + size helpers`.

## Task 3: WorkManager copy worker

**Files:**
- Modify: `app/build.gradle.kts` (add `implementation("androidx.work:work-runtime-ktx:2.10.0")` — verify latest stable; use the version that resolves)
- Create: `system/storage/RecordingCopyWorker.kt`

- [ ] **Step 1:** Add the WorkManager dependency; sync/build to confirm it resolves.
- [ ] **Step 2:** Create the worker. Inputs via `Data`: `srcUri` (string), `destFolderUri` (string), `displayName`, `mimeType`, `deleteLocalAfter` (boolean).
```kotlin
class RecordingCopyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val src = inputData.getString("srcUri")?.toUri() ?: return Result.failure()
        val destFolder = inputData.getString("destFolderUri")?.toUri() ?: return Result.failure()
        val name = inputData.getString("displayName") ?: return Result.failure()
        val mime = inputData.getString("mimeType") ?: "audio/ogg"
        val deleteLocal = inputData.getBoolean("deleteLocalAfter", false)
        if (!SafHelper.isFolderValid(applicationContext, destFolder)) return Result.retry()
        val srcSize = SafHelper.fileSize(applicationContext, src)
        val copied = SafHelper.copyFileToFolder(applicationContext, src, destFolder, name, mime)
            ?: return Result.retry()
        val ok = srcSize <= 0 || SafHelper.fileSize(applicationContext, copied) == srcSize
        if (!ok) { runCatching { DocumentFile.fromSingleUri(applicationContext, copied)?.delete() }; return Result.retry() }
        if (deleteLocal) runCatching { DocumentFile.fromSingleUri(applicationContext, src)?.delete() }
        return Result.success()
    }
}
```
- [ ] **Step 3:** Compile. Commit `feat(storage): WorkManager copy-to-Drive worker`.

## Task 4: StorageRouter

**Files:** Create `system/storage/StorageRouter.kt`

- [ ] **Step 1:**
```kotlin
object StorageRouter {
    fun route(context: Context, localUri: Uri, displayName: String, mimeType: String) {
        val prefs = AppPreferences(context)
        val target = prefs.getStorageTarget()
        if (target == StorageTarget.LOCAL) return                  // nothing to do
        val driveFolder = prefs.getDriveFolderUri() ?: return      // not configured → keep local
        val data = Data.Builder()
            .putString("srcUri", localUri.toString())
            .putString("destFolderUri", driveFolder.toString())
            .putString("displayName", displayName)
            .putString("mimeType", mimeType)
            .putBoolean("deleteLocalAfter", target == StorageTarget.DRIVE)
            .build()
        val req = OneTimeWorkRequestBuilder<RecordingCopyWorker>()
            .setInputData(data)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueue(req)
    }
}
```
- [ ] **Step 2:** Compile. Commit `feat(storage): StorageRouter enqueues per target`.

## Task 5: Hook into the recording finalize

**Files:** Modify `services/recording/RecordingForegroundService.kt`

- [ ] **Step 1:** In `stopRecordingSessionAndService()`, after the file is finalized, route it. The final URI is `uriToRename` (`= activeSession.currentRecordingUri`); the display name comes from the file. Handle BOTH paths so we route the *final* file exactly once:
  - **No-rename path** (phone number present): right after `activeSession.release()`, if `uriToRename != null`, compute the display name via `DocumentFile.fromSingleUri(...).name` and call `StorageRouter.route(applicationContext, uriToRename, name, codecEnum.mimeType)`.
  - **Rename path** (the existing CallLog fallback coroutine): after a successful `renameTo(newName)`, route the renamed file (its URI/name) instead. To avoid double-routing, only auto-route in the no-rename path when the rename branch will NOT run (i.e., when `rawPhoneNumber` is already present), and route inside the rename coroutine otherwise.
  - Keep it simple and correct: a single private helper `routeFinalRecording(uri, name)` called from exactly one place per path.
- [ ] **Step 2:** Build (`assembleDebug`). Commit `feat(recording): route finished recording per storage target`.

## Task 6: Settings UI — target selector + Drive folder picker

**Files:** Modify `ui/screens/SettingsScreen.kt` (+ `SettingsViewModel` if it mediates prefs) and `res/values/strings.xml`

- [ ] **Step 1:** Add a "Storage" section:
  - A selector (dropdown/segmented) for **Storage Target**: Local only / Google Drive only / Both — reads/writes `AppPreferences.getStorageTarget/setStorageTarget`. Reuse the existing dropdown component used for audio source.
  - A **Drive folder** row that launches the SAF tree picker (mirror the recording-folder picker: `ActivityResultContracts.OpenDocumentTree`, `takePersistableUriPermission`, then `setDriveFolderUri`). Show the chosen folder name via `SafHelper.getFolderDisplayNameOrNull`. Only relevant when target ≠ Local.
  - Add the needed string resources.
- [ ] **Step 2:** Build + install. Commit `feat(ui): storage target + Drive folder settings`.

## Task 7: On-device validation

- [ ] **Step 1:** In Settings, pick a **Google Drive** folder and set target = **Both**.
- [ ] **Step 2:** Record a test call. Confirm the `.ogg` is in the local folder immediately, and appears in the **Drive** folder within a few seconds (WorkManager job).
- [ ] **Step 3:** Set target = **Drive only**, record again. Confirm the file lands in Drive and the **local copy is deleted** after verify.
- [ ] **Step 4:** Toggle airplane mode, record with target = Drive; confirm the file stays local and the copy **retries** + completes once network returns. Verify via `adb logcat` (WorkManager) + checking the folders.

**Exit criterion:** recordings route to Local / Drive / Both per the setting, with verify-before-delete and successful retry after a network outage.

## Risks
- **Drive SAF write reliability:** large files / flaky cloud provider — WorkManager retry + size verify mitigate. (Write-only copy to Drive is the supported path; the `rw` limitation only blocked live muxing.)
- **Routing the right (post-rename) file once:** the rename fallback makes this fiddly — Task 5 must route exactly once per recording. Validate the anonymous-call (rename) path in testing.
- **Size verify on Drive:** `DocumentFile.length()` for a freshly-written Drive doc may lag; if flaky, treat a successful copy as success and log a soft warning instead of failing.
