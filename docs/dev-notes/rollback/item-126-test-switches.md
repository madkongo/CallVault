# Rollback: removal of two maintainer test switches (BabApps item 126)

🧪 VERIFYING — built on branch `fix/item-126-remove-two-leftover-test-switches-from-s` (2026-11-16); unit tests
and the translation check are the only checks run. Nothing seen on a phone yet.

## How to roll back

All of it is one branch, merged with `--no-ff`. One command undoes it:

```
git revert -m 1 <merge sha>
```

`<merge sha>` = the merge commit of the branch above into `release/2.5.2`. **Fill it in at merge.**

## What was removed

- The two rows in Settings ▸ Help and about (shown only while debug logging is on): "Skip mDNS when connecting
  (test the port search)" (#65) and "Force Wi-Fi-call capture route" (#45).
- Their reads: `ConnectPortFinder.find` (the normal path is exactly what it was with the switch off) and
  `RecordingForegroundService.onRouteEvent` (now always passes `forced = false`; `WifiCallRoute` still has the
  `forced` input and its tests).
- `AppPreferences.isDebugSkipMdnsConnect/setDebugSkipMdnsConnect/isDebugForceWifiRoute/setDebugForceWifiRoute`,
  the two `SettingsActions` methods, the four strings in all 16 locale files, and the search-index entries.

## What was kept, and the one deliberate exception

- The key names `debug_skip_mdns_connect` and `debug_force_wifi_route` stay in `AppPreferences.Key`.
- **Exception to "never clear a removed feature's stored data":** the owner asked for the stored values to be
  cleared (a phone with the Wi-Fi-call switch on would keep recording phone calls with the app-call capture and
  lose the other side). `AppPreferences.clearRetiredTestSwitches()` removes both keys on every app start
  (`CallVaultApplication.onCreate`, background thread). It is idempotent.

## What users would see after a rollback

The two rows come back under Help and about while debug logging is on, both off. Anyone who had one on before the
update has it off (the values were cleared), so nothing turns itself on. Symptom that would justify a rollback:
a need to run the loopback port search or the Wi-Fi-call route switch on a phone again with no other way to
force it. Then bring them back on a diagnostics branch rather than in the release.
