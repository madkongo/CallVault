# Rollback: Charging only read from dumpsys usb; the property no longer overrides it

🧪 VERIFYING — merged 2026-11-03. 2382 unit tests pass. Not seen on a Honor phone. To settle: the reporter
(Honor BVL-N49, Android 16, standalone mode, Telegram via the maintainer) updates, sets Charging only, and the
"USB is in data mode" warning stays away for a day.

## The bug

- Report 2026-11-03 (debug report 13:14–13:56): the warning said the Default USB Configuration was a data
  mode; the fix button cleared it, and it came back minutes later. The phone was set to Charging only.
- `dumpsys usb` printed no `screen_unlocked_functions` line (none set), so the read returned nothing and fell
  back to `sys.usb.config`, which on this Honor holds `hisuite,mtp,mass_storage` (the vendor default, not the
  setting) → FILE_TRANSFER. Every failed or empty shell read re-wrote the cache with it.

## What changed

- `UsbDefaultConfig.parseDump`: with the USB handler state (`handler={`) in the dump and no
  `screen_unlocked_functions`, the mode is CHARGING (dumpsys leaves out empty fields; the OP9 prints exactly
  this). No handler state → UNKNOWN as before.
- `mayUseProperty`: `sys.usb.config` is read only while the cache is UNKNOWN; it never replaces a mode
  already read. The next good shell read still overwrites any cache.
- Standalone mode only in effect (Shizuku mode resolves the cache to UNKNOWN, as before). No new pref key.

## How to roll back

```
git revert -m 1 3c944be9
```

(`3c944be9` = the merge of `fix/usb-default-honor` into integration/next.)
