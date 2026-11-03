# Rollback: one "Wireless debugging" choice instead of two switches

🧪 VERIFYING — merged 2026-11-03 (maintainer's choice). WirelessDebuggingModeTest + settings tests pass. Seen on the
OP9 in Shizuku mode (greyed, as the old switches were). To settle in built-in mode: pick each choice and check the
behaviour (Keep on: CallVault never switches it off; Keep on, even if I switch it off: it comes back on).

- `WirelessDebuggingMode` maps Only when needed / Keep on / Keep on, even if I switch it off to the unchanged keys
  `wd_enforced` + `wd_never_turn_off`. The old "turn back on" alone reads as the third choice; nothing migrated.
- `WirelessDebuggingModeRow` (dropdown + ⓘ explaining all three) replaces `WirelessDebuggingEnforceToggle` and
  `WirelessDebuggingNeverOffToggle` in ConnectionRows. Setup (onboarding) keeps the enforce switch.
- 7 strings × 15 locales; search index updated.

```
git revert -m 1 7e8bf312
```
