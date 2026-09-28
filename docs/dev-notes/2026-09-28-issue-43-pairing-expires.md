# Issue #43 — the built-in ADB pairing disappears after ~7 days (Galaxy S24), and the app cannot re-pair

**Status:** 📐 researched 2026-09-28 (AOSP source, our code, other projects). Nothing built.
Reporter: likefreddy-lab, Galaxy S24, CallVault 2.4.1, built-in mode, VoIP on, Google Drive storage.

## In plain words

1. **It is Android, by design, on every phone — not a Samsung bug.** Android deletes an ADB pairing that has
   not been *used to connect* for 7 days. CallVault connects over ADB only to start its recorder; once the
   recorder runs, it talks to it directly and never touches ADB again. So a phone where the recorder simply
   keeps working for a week loses the pairing — the more reliable the recorder, the more likely this is.
2. **CallVault cannot notice or recover.** When the connection is refused because the pairing is gone, our
   code throws that specific reason away and just retries; and the "setup complete" flag it saved at pairing
   is never cleared, so the pairing screen is never shown again. Reinstalling was the only way back — what he
   did.
3. **Two known cures:** use the pairing often enough that it never expires, or switch the expiry off. The
   Shizuku forks switch it off; the setting he found ("Disable adb authorization timeout") is exactly that.

## Evidence

**AOSP `AdbDebuggingManager` (android14-release):**
- Wireless debugging's *Paired devices* list is the ADB key store (`getPairedDevices()` iterates `mKeyMap`).
- `filterOutOldKeys()` deletes every key whose last connection is older than
  `Settings.Global.ADB_ALLOWED_CONNECTION_TIME`; `DEFAULT_ADB_ALLOWED_CONNECTION_TIME = 604800000` ms = **7 days**.
  A value of **0** turns the deletion off (`if (allowedTime == 0) return false`).
- The clock is reset only for a key that is connected: on connect, on disconnect, and once a day while
  connected (`MESSAGE_ADB_UPDATE_KEYSTORE`). A key that is never used is deleted 7 days after its last use.
- Developer options' **"Disable adb authorization timeout"** writes that setting to 0.

**Our phones:** OP9 `adb_allowed_connection_time=0` (never expires); OP12 unset → the 7-day default, but it
reconnects constantly (installs, relaunches), which is why we never saw this.

**Our code:**
- `AdbShell` (Wireless-debugging connect): `connect()` throws `AdbPairingRequiredException` for an identity
  adbd no longer trusts; `connectBounded` swallows it to `false` → `BaseConnect.CONNECT_REFUSED`, the same
  answer as a flaky handshake. The launcher then retries forever — his "ADB not connected; retrying".
- `OnboardingStatus.isComplete()` requires `adbConnected`, fed by `adb_paired`, which `setAdbPaired(true)`
  writes on first success and **nothing ever sets back to false**. `AppNavigationScreen.resolveScreen` shows
  the pairing screen only while setup is incomplete, so after setup there is no route back to it.
- Home's `NOT_PAIRED` status ("Setup not complete — finish Wireless Debugging pairing in setup") exists but
  only fires from the missing-prerequisite check, i.e. never for an expired pairing.
- Nothing in the app mentions the authorization timeout.

**Prior art:** Shizuku forks with `WRITE_SECURE_SETTINGS` set `adb_allowed_connection_time` to 0 themselves,
silently: ShizukuPlus (`AdbStarter.stopTcp`), shevery (`AdbDialogFragment`, one of our users runs it),
AxManager. Upstream Shizuku #294 ("have to reinstate wireless debugging every morning") is the same family.

## Proposal (not built — needs the maintainer's choice on point 3)

1. **Recognise a lost pairing (must).** Treat `AdbPairingRequiredException` as its own outcome: clear
   `adb_paired`, log it plainly, stop the relaunch loop, and show on Home "CallVault's pairing expired —
   Pair again", whose button opens the existing pairing screen. Settings, recordings and storage untouched.
2. **A permanent "Pair again" in Settings (must).** The same screen, reachable any time, for this and any
   other lost pairing (a factory-reset Developer options, a revoked authorization).
3. **Stop it expiring — pick one:**
   - **(a) Keep the pairing in use:** a short ADB connection every ~3 days when the recorder has not needed
     one, which resets Android's 7-day clock for our key only. Changes no security setting; costs a brief
     Wireless-debugging switch-on (Wi-Fi needed) or a loopback connect when off-Wi-Fi recording is armed —
     the same thing a recorder relaunch already does.
   - **(b) Switch the expiry off,** like the Shizuku forks — but with consent: a setup step / Settings
     switch explaining that it stops Android forgetting *every* computer ever allowed to debug this phone,
     not just CallVault. One write with the permission we already hold.
   - **Recommendation: (a) by default, (b) offered as an explained option.** (b) alone is a security
     change most users would not knowingly make; (a) alone fails if the phone is off Wi-Fi for 7 days with
     off-Wi-Fi recording off.
4. **Say it during setup:** one line on the pairing step naming the Developer options switch, for users who
   prefer to set it themselves.

Answers to his three questions today: (1) no supported way to re-pair after setup exists; (2)/(3) the
proposal above.
