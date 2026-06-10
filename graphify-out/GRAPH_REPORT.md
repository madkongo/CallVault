# Graph Report - callrecorder  (2026-06-10)

## Corpus Check
- 83 files · ~81,349 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 921 nodes · 1533 edges · 59 communities (49 shown, 10 thin omitted)
- Extraction: 98% EXTRACTED · 2% INFERRED · 0% AMBIGUOUS · INFERRED: 36 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `cc8e51ad`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- [[_COMMUNITY_Community 0|Community 0]]
- [[_COMMUNITY_Community 1|Community 1]]
- [[_COMMUNITY_Community 2|Community 2]]
- [[_COMMUNITY_Community 3|Community 3]]
- [[_COMMUNITY_Community 4|Community 4]]
- [[_COMMUNITY_Community 5|Community 5]]
- [[_COMMUNITY_Community 6|Community 6]]
- [[_COMMUNITY_Community 7|Community 7]]
- [[_COMMUNITY_Community 8|Community 8]]
- [[_COMMUNITY_Community 9|Community 9]]
- [[_COMMUNITY_Community 10|Community 10]]
- [[_COMMUNITY_Community 11|Community 11]]
- [[_COMMUNITY_Community 12|Community 12]]
- [[_COMMUNITY_Community 13|Community 13]]
- [[_COMMUNITY_Community 14|Community 14]]
- [[_COMMUNITY_Community 15|Community 15]]
- [[_COMMUNITY_Community 16|Community 16]]
- [[_COMMUNITY_Community 17|Community 17]]
- [[_COMMUNITY_Community 18|Community 18]]
- [[_COMMUNITY_Community 19|Community 19]]
- [[_COMMUNITY_Community 21|Community 21]]
- [[_COMMUNITY_Community 22|Community 22]]
- [[_COMMUNITY_Community 23|Community 23]]
- [[_COMMUNITY_Community 24|Community 24]]
- [[_COMMUNITY_Community 25|Community 25]]
- [[_COMMUNITY_Community 26|Community 26]]
- [[_COMMUNITY_Community 27|Community 27]]
- [[_COMMUNITY_Community 28|Community 28]]
- [[_COMMUNITY_Community 29|Community 29]]
- [[_COMMUNITY_Community 30|Community 30]]
- [[_COMMUNITY_Community 31|Community 31]]
- [[_COMMUNITY_Community 32|Community 32]]
- [[_COMMUNITY_Community 33|Community 33]]
- [[_COMMUNITY_Community 34|Community 34]]
- [[_COMMUNITY_Community 35|Community 35]]
- [[_COMMUNITY_Community 36|Community 36]]
- [[_COMMUNITY_Community 37|Community 37]]
- [[_COMMUNITY_Community 38|Community 38]]
- [[_COMMUNITY_Community 39|Community 39]]
- [[_COMMUNITY_Community 40|Community 40]]
- [[_COMMUNITY_Community 45|Community 45]]
- [[_COMMUNITY_Community 46|Community 46]]
- [[_COMMUNITY_Community 47|Community 47]]
- [[_COMMUNITY_Community 48|Community 48]]
- [[_COMMUNITY_Community 49|Community 49]]
- [[_COMMUNITY_Community 50|Community 50]]
- [[_COMMUNITY_Community 51|Community 51]]
- [[_COMMUNITY_Community 52|Community 52]]
- [[_COMMUNITY_Community 53|Community 53]]
- [[_COMMUNITY_Community 54|Community 54]]
- [[_COMMUNITY_Community 55|Community 55]]
- [[_COMMUNITY_Community 56|Community 56]]
- [[_COMMUNITY_Community 57|Community 57]]
- [[_COMMUNITY_Community 58|Community 58]]
- [[_COMMUNITY_Community 59|Community 59]]

## God Nodes (most connected - your core abstractions)
1. `AppPreferences` - 67 edges
2. `SettingsViewModel` - 34 edges
3. `Boolean` - 32 edges
4. `SettingsActions` - 30 edges
5. `RecordingForegroundService` - 20 edges
6. `Notification` - 19 edges
7. `AppLogger` - 19 edges
8. `Boolean` - 17 edges
9. `AdbPairingService` - 17 edges
10. `AdbPairingService` - 17 edges

## Surprising Connections (you probably didn't know these)
- `PermissionsScreen()` --calls--> `AppPreferences`  [INFERRED]
  app-src/app/src/main/java/com/kitsumed/shizucallrecorder/ui/screens/PermissionsScreen.kt → app-src/app/src/main/java/com/kitsumed/shizucallrecorder/onboarding/OnboardingStatus.kt
- `SettingsScreen()` --calls--> `PersistentFolderPickerContract`  [INFERRED]
  app-src/app/src/main/java/com/kitsumed/shizucallrecorder/ui/screens/SettingsScreen.kt → app-src/app/src/main/java/com/kitsumed/shizucallrecorder/system/SystemIntentHelpers.kt
- `AppNavigationScreen()` --calls--> `SettingsScreen()`  [INFERRED]
  app-src/app/src/main/java/com/kitsumed/shizucallrecorder/AppNavigationScreen.kt → app-src/app/src/main/java/com/kitsumed/shizucallrecorder/ui/screens/SettingsScreen.kt
- `SettingsContent()` --calls--> `ContactSelectionDialog()`  [INFERRED]
  app-src/app/src/main/java/com/kitsumed/shizucallrecorder/ui/screens/SettingsScreen.kt → app-src/app/src/main/java/com/kitsumed/shizucallrecorder/ui/common/ContactSelectionDialog.kt
- `RecordingSection()` --calls--> `FileNameFormatDialog()`  [INFERRED]
  app-src/app/src/main/java/com/kitsumed/shizucallrecorder/ui/screens/SettingsScreen.kt → app-src/app/src/main/java/com/kitsumed/shizucallrecorder/ui/common/FileNameFormatDialog.kt

## Import Cycles
- None detected.

## Communities (59 total, 10 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.18
Nodes (10): CallVault Fork Implementation Plan, Environment Facts (verified 2026-06-09), Phase 2 (deferred — separate plan), Scope Note, Self-Review Notes, Task 1: Set up the build toolchain, Task 2: Clone the upstream fork and verify a baseline build, Task 3: Rename to CallVault (license-compliant) and rebuild (+2 more)

### Community 2 - "Community 2"
Cohesion: 0.06
Nodes (10): Boolean, String, AppPreferences, DefaultsValue, IgnoreContactsMode, Key, ThemeMode, Set (+2 more)

### Community 3 - "Community 3"
Cohesion: 0.07
Nodes (8): android, AppPreferences, Int, String, Boolean, DebugAction, SettingsActions, SettingsViewModel

### Community 4 - "Community 4"
Cohesion: 0.15
Nodes (31): List, Modifier, String, Boolean, Modifier, String, AppPreferences, Int (+23 more)

### Community 5 - "Community 5"
Cohesion: 0.08
Nodes (21): Long, ScrcpyAudioCodec, ScrcpyClient, ScrcpyAudioCodec, String, Boolean, Context, File (+13 more)

### Community 6 - "Community 6"
Cohesion: 0.40
Nodes (3): CoroutineWorker, Result, RecordingCopyWorker

### Community 7 - "Community 7"
Cohesion: 0.08
Nodes (24): Boolean, Context, CoroutineScope, IShellService, Job, ParcelFileDescriptor, RecordingMetadata, ScrcpyAudioCodec (+16 more)

### Community 8 - "Community 8"
Cohesion: 0.21
Nodes (10): AppPreferences, Boolean, Context, File, ILogCallback, String, Uri, BufferedWriter (+2 more)

### Community 9 - "Community 9"
Cohesion: 0.10
Nodes (14): AndroidViewModel, OnboardingStatus, StateFlow, List, Set, StateFlow, String, OnboardingStatus (+6 more)

### Community 10 - "Community 10"
Cohesion: 0.11
Nodes (20): AppPreferences, Boolean, Context, Int, Job, RecordingMetadata, Set, String (+12 more)

### Community 11 - "Community 11"
Cohesion: 0.17
Nodes (12): Boolean, CoroutineScope, ILogCallback, Int, Job, ParcelFileDescriptor, String, IShellService (+4 more)

### Community 12 - "Community 12"
Cohesion: 0.21
Nodes (16): Boolean, Context, IShellService, Long, String, ServiceConnection, Shizuku, checkServerPermission() (+8 more)

### Community 13 - "Community 13"
Cohesion: 0.08
Nodes (16): AdbMdns, discoverPort(), DiscoveryListener, ResolveListener, Context, Int, String, Context (+8 more)

### Community 14 - "Community 14"
Cohesion: 0.23
Nodes (13): ActivityResultContracts, Context, Intent, String, Uri, copyToClipboard(), launchSmartIntent(), openAppSettings() (+5 more)

### Community 15 - "Community 15"
Cohesion: 0.16
Nodes (18): AbsAdbConnectionManager, AdbConnectionManager, generateAndPersistIdentity(), getInstance(), loadCertificate(), loadPrivateKey(), Context, String (+10 more)

### Community 16 - "Community 16"
Cohesion: 0.18
Nodes (6): Context, RecordingNotificationHelper, RecordingServiceState, SpikeActions, String, VibrationEffect

### Community 17 - "Community 17"
Cohesion: 0.11
Nodes (16): Boolean, Context, Boolean, String, Uri, Context, String, Uri (+8 more)

### Community 18 - "Community 18"
Cohesion: 0.24
Nodes (8): DownloadAssetTask, ExtractMetadataTask, String, DefaultTask, DirectoryProperty, File, Property, Sync

### Community 19 - "Community 19"
Cohesion: 0.24
Nodes (7): Context, Int, ScrcpyAudioCodec, ScrcpyAudioSource, String, List, ScrcpyConfig

### Community 21 - "Community 21"
Cohesion: 0.44
Nodes (3): Boolean, Context, PermissionChecks

### Community 22 - "Community 22"
Cohesion: 0.31
Nodes (6): Context, RecordingMetadata, ScrcpyAudioCodec, String, FileNamePlaceholder, RecordingFileNameFormatter

### Community 23 - "Community 23"
Cohesion: 0.22
Nodes (8): Contributing, Disclaimer, Features, Installation, License, Requirements, ShizuCallRecorder, Star History

### Community 24 - "Community 24"
Cohesion: 0.06
Nodes (41): OnboardingStatus, Context, Boolean, List, Modifier, Set, String, String (+33 more)

### Community 25 - "Community 25"
Cohesion: 0.25
Nodes (7): Configuration Guide, Configuring ShizuCallRecorder, Installing Shizuku, Introduction, Method 1, Method 2, The Two Method

### Community 26 - "Community 26"
Cohesion: 0.29
Nodes (6): Reporting a Vulnerability, Responsible Disclosure, Security Policy, Supported Versions, Testing Before Reporting, What Not to Report

### Community 27 - "Community 27"
Cohesion: 0.29
Nodes (6): description, developers, licenses, name, uniqueId, website

### Community 28 - "Community 28"
Cohesion: 0.29
Nodes (6): description, developers, licenses, name, uniqueId, website

### Community 29 - "Community 29"
Cohesion: 0.40
Nodes (4): Context, Parcelable, enrichMetadata(), RecordingMetadata

### Community 31 - "Community 31"
Cohesion: 0.18
Nodes (7): Context, Intent, Context, Intent, BootReceiver, BroadcastReceiver, PhoneStateReceiver

### Community 32 - "Community 32"
Cohesion: 0.53
Nodes (5): RecordingMetadata, Active, RecordingServiceState, Standby, Starting

### Community 33 - "Community 33"
Cohesion: 0.40
Nodes (4): content, hash, name, url

### Community 34 - "Community 34"
Cohesion: 0.50
Nodes (3): Contributing, Project Creation History, Rules

### Community 46 - "Community 46"
Cohesion: 0.10
Nodes (19): Architecture, CallVault Unified — Design Spec (Option B), Data Flow, Error Handling, Goals, Implementation Order (high level), Licensing, Motivation (+11 more)

### Community 47 - "Community 47"
Cohesion: 0.14
Nodes (13): CallVault Unified — Plan 1: Embedded-ADB De-risk Spike, Decomposition (this is Plan 1 of 4), Environment Facts (verified 2026-06-09), Reference implementations (port, don't invent), Spike Exit Criterion (GATE), Task 1: Add JitPack + libadb-android and confirm it builds, Task 2: Port the ADB connection manager, Task 3: Throwaway debug Activity to drive the spike (+5 more)

### Community 48 - "Community 48"
Cohesion: 0.12
Nodes (15): Architecture Boundaries, Build & Test Loop, CallVault — Design Spec, Data Flow, Goals, Licensing Constraint, Non-Goals, Open Questions (+7 more)

### Community 49 - "Community 49"
Cohesion: 0.08
Nodes (20): AdbPairingService, start(), AdbMdns, Context, Int, String, Context, Intent (+12 more)

### Community 50 - "Community 50"
Cohesion: 0.25
Nodes (7): Key insight reaffirmed, Known follow-ups (Plan 3), Plan 2 — ADB Recording Findings & Status, Plan 4 follow-ups, Result: 🟢 GREEN — the fused app records real calls over embedded ADB, no Shizuku, Two bugs found by on-device testing (fixed), What was validated on-device

### Community 51 - "Community 51"
Cohesion: 0.22
Nodes (8): Caveats / open questions for later plans, Confirmed library API (libadb-android 3.1.1), Decision: 🟢 GREEN — proceed to Plan 2, Dependencies added, Embedded-ADB Spike — Findings & Go/No-Go, Spike artifacts (on branch `feat/callvault-adb-spike`), The three fixes that made it work (don't lose these), What was validated (on-device evidence)

### Community 52 - "Community 52"
Cohesion: 0.40
Nodes (3): String, SpikeLog, StateFlow

### Community 53 - "Community 53"
Cohesion: 0.13
Nodes (14): CallVault Unified — Plan 2: Embedded-ADB Recording Transport (replace Shizuku), Decomposition (Plan 2 of 4), File Structure, Key facts established (don't re-derive), Proven references (don't design from scratch — mirror these), Risks, Task 1: GATE — prove scrcpy audio over the ADB socket (on-device), Task 2: Promote the spike ADB classes into a production package (+6 more)

### Community 54 - "Community 54"
Cohesion: 0.29
Nodes (6): Context, Int, ScrcpyAudioCodec, ScrcpyAudioSource, ScrcpyLauncher, start()

### Community 55 - "Community 55"
Cohesion: 0.29
Nodes (6): Bugs found by on-device testing (fixed), Follow-ups (Plan 4), Implemented (this plan), Not yet exercised on-device (implemented, low risk), Plan 3 — Storage Routing Findings & Status, Result: 🟢 GREEN — recordings auto-copy to Google Drive

### Community 56 - "Community 56"
Cohesion: 0.15
Nodes (12): CallVault Unified — Plan 3: Storage Routing (Local / Drive / Both), Decomposition (Plan 3 of N), Facts established (reuse — don't reinvent), File Structure, Risks, Task 1: Storage preferences, Task 2: SafHelper copy (write-only) + size, Task 3: WorkManager copy worker (+4 more)

### Community 58 - "Community 58"
Cohesion: 0.36
Nodes (4): AdbShell, AdbStream, Context, String

### Community 59 - "Community 59"
Cohesion: 0.13
Nodes (14): CallVault Unified — Plan 4: Shizuku Removal + Hands-Free Boot, Decomposition (Plan 4 of 4), Risks, Task A1: Remove the spike package + manifest entries, Task A2: Strip Shizuku from the recording service + prefs, Task A3: Remove Shizuku settings UI + helpers + URLs + logger refs, Task A4: Delete Shizuku classes, AIDL, deps, manifest provider, Task B0: GATE — empirical reboot behaviour (decides Part B design) (+6 more)

## Knowledge Gaps
- **250 isolated node(s):** `String`, `Context`, `Context`, `Intent`, `Decomposition (Plan 4 of 4)` (+245 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **10 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `AppPreferences` connect `Community 17` to `Community 24`, `Community 7`?**
  _High betweenness centrality (0.145) - this node is a cross-community bridge._
- **Why does `RecordingForegroundService` connect `Community 7` to `Community 16`, `Community 17`, `Community 3`, `Community 49`?**
  _High betweenness centrality (0.132) - this node is a cross-community bridge._
- **Why does `Boolean` connect `Community 3` to `Community 16`, `Community 58`, `Community 7`?**
  _High betweenness centrality (0.086) - this node is a cross-community bridge._
- **What connects `String`, `Context`, `Context` to the rest of the system?**
  _250 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 2` be split into smaller, more focused modules?**
  _Cohesion score 0.061754385964912284 - nodes in this community are weakly interconnected._
- **Should `Community 3` be split into smaller, more focused modules?**
  _Cohesion score 0.0673076923076923 - nodes in this community are weakly interconnected._
- **Should `Community 5` be split into smaller, more focused modules?**
  _Cohesion score 0.08367071524966262 - nodes in this community are weakly interconnected._