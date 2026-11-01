# WATCH: Android is narrowing on-device ADB — the mechanism CallVault depends on

**Status: 📐 TRACKING / not yet actioned.** This is a living watch note, not a task. It exists so the
strategic risk is not forgotten between sessions. Update it when any signal below moves.

## Why this matters

CallVault's entire non-root capture path depends on an on-device process reaching the phone's own adbd
over **loopback (127.0.0.1)** while Wireless Debugging is active — the same mechanism Shizuku, LADB and
libadb-android use. That reachability is a *side effect* of adbd binding `INADDR_ANY` when the WD listener
is up. If Android restricts adbd's wireless listener to the `wlan0` interface (or otherwise removes
loopback reachability), **the loopback bootstrap breaks and there is no non-root replacement** — the
2026-10-23 research (`2026-10-23-wifi-free-adb-bootstrap-research.md`) proved no other door exists.

This is a bigger long-term risk than the off-Wi-Fi reboot gap. The off-Wi-Fi gap costs some users some
calls; this would end the non-root path entirely.

## Signals being tracked (as of 2026-10-23)

1. **AOSP issue-tracker proposal to restrict on-device adb to `wlan0`.** Surfaced via a secondary source
   (Gadget Hacks, "on-device ADB" article, attributed to researcher "Kitsumed"). **UNVERIFIED against the
   primary tracker — do that first.** If adopted, loopback access for Shizuku/LADB/CallVault is removed.
   - TODO: find the exact Google Issue Tracker ID and read the primary thread; record the feature-request
     number and current status here. (The r/fossdroid thread below links the blog but not the ID.)
2. **Community blog post + discussion:** "Android May Soon Restrict On-Device ADB, Affecting Shizuku,
   libadb and Developers." Reddit: r/fossdroid `1v5jtr6` (137 up), mirror r/degoogle `1v5m1rn`
   (2026-07-24). Framed as a *potential future* change under discussion, not shipped.
3. **CVE-2026-0073** — a network attacker could skip the wireless-ADB pairing step. Pushes Google toward
   *tightening* wireless debugging (auth/pairing hardening), i.e. the trend is restriction, not opening.
   - TODO: confirm the CVE details and which Android/adb versions carry the fix; note any behavior change
     to pairing we must adapt to.
4. **adb Wi-Fi 2.0 (Android 17 / adb 37.0.0)** — usability changes to pairing, still Wi-Fi-gated; not a
   restriction by itself, but it is the active area of churn. Related:
   `2026-10-20-android-17-call-recording-research.md` and `2026-09-19-android-17-adb-detection-issue-40.md`.

## What to do if a restriction looks real (not yet decided — options only)

- Confirm scope: does it remove *loopback* reachability, or only external-LAN reachability? Only the
  former breaks us. The loopback path may survive if the restriction is "no off-device connections."
- If loopback is removed: the non-root path is gone; the honest fallbacks are root (opt-in) or the
  OEM-native-recording ingest idea (device-limited; recording-core, so deprioritized by the maintainer).
- Target-SDK timing: we already owe an API-37 bump for mDNS; fold any pairing/transport changes into that.

## Verification owed before trusting signals 1 & 3

Both came from a secondary source in the research pass. Per the project's doc-status rule, treat them as
🧪 leads until the primary AOSP tracker / CVE record is read. Do that before acting on this note.

Related memory: [[wifi-free-adb-bootstrap-impossible]], [[android-17-call-recording-research]],
[[debugging-switches-model]].
