# UI/UX critique — CallVault 2.3.0-rc6

**Date:** 2026-09-06 · **Build assessed:** 2.3.0-rc6 (versionCode 20334) · **Device:** OnePlus 12 (CPH2581), 1440x3168 @ density 4.0, 80 real recordings, dark theme
**Method:** Impeccable `critique`, dual-agent (design review + mechanical evidence, run isolated from each other)
**Status of this document:** 🧪 ASSESSMENT ONLY. No code was changed. Nothing here is a decision.

Claims are labelled: **[measured]** = observed on the device or counted in source; **[judged]** = design opinion; **📐 CALCULATED** = derived from reading code, never observed.

---

## Design Health Score — 30/40 (Good)

| # | Heuristic | Score | Key issue |
|---|-----------|-------|-----------|
| 1 | Visibility of System Status | 4 | Hero card, per-cause failure sentences, determinate transcription ring. Gap: Home never says "recording right now" — the moment users most want confirmation lives only in a system notification. |
| 2 | Match System / Real World | 3 | Copy is outstanding plain English. But the first row in Settings is "Shizuku mode", whose own text says "CallVault does not need it". |
| 3 | User Control and Freedom | 2 | No undo anywhere. 11 `Toast.makeText`, 0 `Snackbar` **[measured]** — a Toast cannot carry an Undo. |
| 4 | Consistency and Standards | 2 | Internally near-perfect; against the platform: 92%-width drawer for a leaf screen, 40dp targets by written policy, Toasts for Snackbars, no window size classes, no predictive-back opt-in, no reduced-motion support. |
| 5 | Error Prevention | 4 | Cloud folders rejected at the picker; transcribe gate refuses over-long files before asking language; delete dialog names what survives. |
| 6 | Recognition Rather Than Recall | 3 | Filter chips carry their value. But pairing requires carrying a 6-digit code between two system surfaces. |
| 7 | Flexibility and Efficiency | 3 | Speed cycling, ±10s, multi-select, cross-library transcript search. But both scrubbers are pointer-only and there is no sort control. |
| 8 | Aesthetic and Minimalist Design | 3 | Dark theme is handsome, the row is disciplined. Against it: Home can stack hero + 3 banners above the list; the "Top" pill overlaps content; a donation pill owns the app-bar title slot. |
| 9 | Error Recovery | 4 | `FailureReason` and `Prerequisite` each get their own sentence. Gap: merge refusal is a Toast. |
| 10 | Help and Documentation | 2 | No in-app help. The wizard cannot be re-run. Debug only works if logging was on before the incident. |
| **Total** | | **30/40** | **Good** |

Score 4 was lowered from the design reviewer's 3 after the mechanical pass: the platform-standard divergences are systemic, not incidental.

## Cognitive load — 4 of 8 failed (CRITICAL band)

**Failed:** single focus · chunking (≤4 per group) · minimal choices (≤4 visible options) · working memory.
**Passed:** grouping · visual hierarchy · one-thing-at-a-time · progressive disclosure.

Decision points over four **[measured]**: Home filter bar = 6 controls, all defaulted to "All"; Settings root = 8 items; wizard = 9 steps; row overflow = 4 (at the limit).

## Design specificity — authored where it counts, generic everywhere else **[judged]**

The parts only a call recorder needs got the thinking: the row's play disc with an overhanging call-origin badge, one transcript slot carrying four meanings with a determinate ring, the waveform *as* the scrubber, `SetupHealth` mapping every failure cause to its own sentence, `BidiText` deciding direction per line from the first strong character. None of that transfers to another app.

The chrome around it is not authored. Everything is a 22dp bordered card on a flat ground, one density, Roboto, one accent. No typographic voice, no signature moment. It reads as an unusually disciplined developer's app rather than a designed product — the design system was defined *defensively* (every comment is about which Material default renders red here) rather than expressively.

## Deterministic scan — the bundled detector produced NOTHING

`detect.mjs` exited 0 with `[]` on every target. Root cause: its `SCANNABLE_EXTENSIONS` allowlist covers web markup only — `.kt` and `.xml` are absent. It walked both trees, matched 0 files, exited clean. **This is "nothing was scanned", not "no problems found."** All mechanical evidence below was hand-rolled.

## What's working

1. **The failure vocabulary** (`ui/screens/HomeScreen.kt:1490-1533`). Five failure causes and five prerequisites each get their own sentence, naming the affected call and when. The green tick is honest — it says *when it was last verified*, not "everything's fine". This is the right instinct for an app whose only real failure mode is silence.
2. **One transcript slot with four meanings** (`ui/common/TranscriptActionButton.kt`). Transcribe / in-progress-with-% / read / retry share one 40dp position. At 80 rows the eye learns one position; the determinate ring fixes the specific reported problem that a spinner turning for eight minutes is indistinguishable from a hang.
3. **i18n discipline** **[measured]**: 590 `stringResource` uses, **0** hardcoded user-facing strings, across 11 locales. `BidiText.isRtl` decides per line from the first strong character rather than the app locale — correct for all 99 languages whisper handles, not just the eleven in the dropdown. Almost nobody ships this.

## Priority issues

### P0 — Both scrubbers are invisible to assistive tech
`ui/common/WaveformBar.kt:41` and `ui/common/SeekBar.kt:41` are raw `Canvas`/`Box` with `pointerInput` only. No `Role`, no `ProgressBarRangeInfo`, no `SemanticsActions.SetProgress`, no `contentDescription`. Corroborated mechanically: `stateDescription` = **0** app-wide, `Modifier.semantics` = **1** **[measured]**.

The waveform is the *primary* control on Playback — the Material Slider was deliberately removed. A TalkBack, switch-access or external-keyboard user cannot seek at all. On a 30-minute call that is 180 taps of ±10s to reach the middle. `SeekBar` is shared with the transcript sheet, so the same wall appears twice.

**Fix:** add `Modifier.semantics { role = Role.Slider; progressBarRangeInfo = ProgressBarRangeInfo(position, 0f..duration); setProgress { onSeek(it); true }; contentDescription = ... }` to both. `setProgress` is what enables TalkBack volume-key scrubbing and switch access. ~10 lines each.

### P1 — Settings is a 92%-width flipped drawer and Home bleeds through the edge
`ui/common/SettingsSidebar.kt:84` — `SHEET_WIDTH_FRACTION = 0.92f`, rendered by flipping `LocalLayoutDirection` so `ModalNavigationDrawer` opens from the physical right.

Screenshots show a truncated "C" from the CallVault title and clipped card edges down the left gutter. It reads as a rendering bug, not as depth. A navigation drawer is for switching top-level destinations; Settings is a leaf. **In the RTL locales the file's own doc comment is false** — ambient direction is already RTL so the panel still arrives from the physical right, while the gear that opens it has moved to the physical left. That is wrong in 2 of the 11 shipped locales.

**Fix:** make Settings a full-width destination (predictive back comes free), or `fillMaxWidth()` with a proper scrim. A deliberate 16–24dp of scrim is a design; 8% of a clipped Home screenshot is not.

### P1 — Touch targets below the platform minimum, by written policy
Measured on device at density 4.0 (48dp = 192px) **[measured]**:

| Element | Measured | Source |
|---|---|---|
| Filter chips ("Source: All" etc.) | 453×**168**px = 113×**42**dp | `HomeScreen.kt:1714`, `:1784` |
| "Transcribe this call", every row | **160**×192px = **40**×48dp | `ROW_ACTION_SIZE`, `HomeScreen.kt:2651` |
| Favourite / delete on Playback | 40dp | `ACTION_TARGET`, `PlaybackScreen.kt:473` |
| Tag-remove `×` | `.size(16.dp).clickable{}` | `PlaybackScreen.kt:779-780` |
| Adjacent filter chips | **9px gap** = 2.25dp (needs 8dp) | — |

`minimumInteractiveComponentSize` / `defaultMinSize`: **0 occurrences app-wide**.

The 16dp `×` is destructive and sits inside a chip body that manages that tag across the whole library. The 40dp targets are the two most-tapped controls in the app; the trade recorded in the code comments buys ~5 characters of a name that ellipsizes anyway.

**Fix:** wrap the `×` in a 48dp `IconButton` (glyph stays 16dp); `heightIn(min = 48.dp)` on the chips; return row actions to 48dp and take the width from `META_INDENT`.

### P1 — Filtering to zero results says "No recordings yet"
`HomeScreen.kt:666` uses `uiState.filteredRecordings`; `:712-716` renders `EmptyRecordings()` whenever *that* is empty. The copy (`:1858`) reads *"No recordings yet — When CallVault records a call, it shows up here. Make or take a call to get started."*

A user with 80 recordings who filters to Contact + Direction and gets no match is told their library is empty and instructed to make a call — while the filter bar stays on screen above it, contradicting the message. Given this project's history of "my recordings are missing" reports, this is a support ticket waiting to happen.

**Fix:** branch on `uiState.recordings.isEmpty()` for the true empty state; otherwise "No recordings match these filters" + a **Clear filters** action.

### P2 — Seven colour roles fall through to M3 defaults in light mode 📐 CALCULATED
`ui/theme/Theme.kt` **[measured]**: `tertiaryContainer`, `onTertiaryContainer` and `scrim` are set in dark and **unset in light**; `inverseSurface`, `inverseOnSurface`, `inversePrimary`, `surfaceTint` are unset in **both**.

This project's own `m3-default-colors-render-red` note records that M3 defaults resolve to CoralDeep in this scheme. Not observed in the light-theme capture — but these seven roles are the mechanically-identified exposure and should be pinned before someone finds them.

### P2 — Light-theme primary fails WCAG AA as text
`ui/theme/Color.kt:53` — `TealOnLight = #0E8C7E` used as `primary`. Against white surface that is **4.14:1**; on tinted pill backgrounds ~4.0:1. It is used as *small* text: Support pill (12sp), active filter labels (14sp), "+ Add tag", wizard eyebrow (11sp). All fail AA's 4.5:1. Dark theme measures 9.8:1 and is fine — light is the second-class scheme.

**Fix:** darken to ≈`#0A7466` (~5.6:1) for text roles, or keep the current value only for fills and icons ≥24dp.

### P2 — The "Top" pill overlaps list content and arrives too early
`HomeScreen.kt:774-801` — `visible = firstVisibleItemIndex > 3`, aligned `TopCenter`, opaque `primaryContainer`, no elevation. It sits *on top of* a recording card with no shadow to explain that it floats, appears after four items, and TopCenter is the hardest place to reach one-handed. Material's pattern is an extended FAB bottom-end. The app has **0 FABs** **[measured]**, so nothing conflicts.

### P2 — Hardcoded date format and mixed numeral systems
`HomeScreen.kt:2723` — `SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())`; `:2732`/`:2743` force `Locale.US` for durations. "03/09/26" is 3 September here and 9 March in the US, in an app whose value is knowing which call this is. Hardcoded `HH:mm` ignores the device 12/24-hour preference. An Arabic-locale user gets Eastern-Arabic numerals in the date and Western numerals in the duration **on the same line**.

The same function is also a recomposition hazard: it allocates `SimpleDateFormat` + `Date` + **three** `Calendar.getInstance()` per call, unremembered, once per visible row per recomposition **[measured]**.

**Fix:** `DateUtils.formatDateTime(...)`. The app already does this correctly at `HomeScreen.kt:1553`, where the comment says "the platform's own phrasing, so it is localised for free".

## Persona red flags

**Jordan (first-timer).** The permissions screen shows 6–8 pending cards and **one** button whose label says "Pair" and whose action forks three ways on hidden device state (`PermissionsScreen.kt:330-345`) — nothing connects the button to a card. Tapping it launches system Wireless Debugging; CallVault is gone and the next instruction exists **only in a notification**. If notifications were denied on the first card, the flow has no surface at all and nothing says so. Then a 9-step wizard with no map, no back-review, and no way to re-run it.

**Sam (screen reader / keyboard / 200% zoom).** Cannot seek (P0). Filter chips are bare `clickable` Rows with no `Role` and no popup semantics. Selection mode is conveyed by fill colour + a glyph with `contentDescription = null`, no `stateDescription` — a screen-reader user in multi-select cannot tell which rows are selected. At **130%** font scale the playback header already truncates "1 hour ago, 14:28" → "1 hour ago, …" **[measured, screenshot]**. `imePadding()`: **0 occurrences app-wide** **[measured]**, with `enableEdgeToEdge()` on and the Note field the last card on the screen.

**Riley (stress tester).** 0 results ≠ 0 items (P1). At 1000 items, `HomeScreen.kt:242` builds `listedNames = filteredRecordings.map { it.displayName }` on every recomposition and uses it as a `remember` key — a 1000-element `List.equals()` per frame. Every identifying label is `maxLines = 1`, so two 60-character names sharing a prefix are indistinguishable. Rotating during a merge cancels it half-done with nothing on screen recording what completed. Emoji are handled correctly, by design.

## Minor observations

- 19 animation sites, **0** references to `ANIMATOR_DURATION_SCALE` / `areAnimationsEnabled` **[measured]** — "Remove animations" is not honoured anywhere.
- `android:enableOnBackInvokedCallback` absent from the manifest; 0 predictive-back references **[measured]**. No trap found — every unconditional `BackHandler` sits in a branch with an exit.
- 5 files in `ui/` breach the project's own 800-line cap: `SettingsScreen.kt` 2956, `HomeScreen.kt` 2745, `WizardScreen.kt` 1173, `HomeViewModel.kt` 1045, `PlaybackScreen.kt` 985.
- `ROW_ACTION_SIZE` and `ACTION_TARGET` are the same 40dp value defined twice with near-identical justifying comments.
- `INLINE_PLAYER_ENABLED = false` keeps ~200 lines of dead player code alive inside a 2745-line file.
- `WhatsNewDialog`'s dismiss slot is "Open Settings" and its confirm is "OK" — a navigation action in the destructive-position slot inverts Material's button semantics.
- No window-size-class handling at all: on a tablet or unfolded foldable, Home is one 1000dp-wide column and the Settings drawer covers 92% of a 10-inch screen.
- Only 3 real hardcoded `fontSize` call sites **[measured]** — `MergeProgressDialog.kt:132/138` and `TranscriptActionButton.kt:136` (9.sp, two below the scale's 11sp floor). 158 uses of the type scale against them. Colour is fully tokenised: **0** raw colour literals outside `ui/theme/`.
- Three recomposition hazards confirmed **[measured]**: `HomeScreen.kt:2713/2723` (per-row date allocation), `SettingsScreen.kt:1512-1513` (option list rebuilt every recomposition), `TranscriptSearch.kt:69` (regex compiled per search hit).

## False positives, so they are not re-reported

| Scan | Raw | Real | Why the rest are noise |
|---|---|---|---|
| Hardcoded font sizes | 15 | **3** | 13 are `Type.kt` — the scale definition itself |
| Hardcoded `label=`/`title=` strings | 10 | **0** | 6 animation labels, 4 inside `@Preview` |
| Unlabelled clickables (device dump) | 24 | **0** | Compose wrapper nodes; every one has a labelled descendant |
| Sub-minimum touch targets (device) | 8 | **4** | 4 were the last list row clipped at the viewport edge |
| Adjacency violations | 19+3 | **3** | 19 were structural nesting (row encloses its own buttons) |
| Recomposition hazards | 7 | **3** | 4 were inside `remember`, at object scope, or on the IO dispatcher |

Note for future runs: the brief assumed density 3.5 (48dp = 168px). The OP12 measures **density 4.0, so 48dp = 192px**. At the wrong threshold the touch-target count would have been 6 instead of 4.

## Questions worth sitting with

1. If the one thing this app must never do is fail silently, why can "was my last call recorded?" only be answered by a card reporting when *setup* was last verified — while the list, where people actually look, shows a missed call as nothing at all?
2. Settings covers 92% of the screen and Home is unusable behind it. What is the remaining 8% buying that a full-screen destination with predictive back would not?
3. The two most-tapped controls on every row are 40dp so the contact name gains 20dp. The name ellipsizes anyway. Which did you buy: five characters, or a permanently under-spec target pressed eighty times a session?
4. Every colour is hand-authored and every Material default is defended against in a comment. On the one app whose promise is that this is the user's own private record, why is dynamic colour a hidden opt-in rather than the thing the design defends *for*?
5. A donation pill occupies the app-bar title slot on every launch of a privacy tool. What does that cost in trust on the fiftieth visit against what it earns on the first?

---

**Evidence:** screenshots captured from the live rc6 build (Home dark/light, mid-scroll, Settings, Settings expanded, Playback, Playback @130% font scale). Device settings were restored (dark theme, font scale 1.0); nothing in the app was toggled.
