# Rollback: `feat/open-adb-port-probe`

🧪 VERIFYING — built 2026-10-31, unit tests pass, **not run on a phone** (and none of our phones has a ROM
that opens adbd's tcp port by itself, so the success path is unproven anywhere).

## What changed

- `AdbShell.ensureConnected` gained one last-resort transport, `connectAlreadyOpenPort`: a tcp port adbd was
  **already** listening on without anyone arming it. Candidates are ONLY ports a system property names —
  `service.adb.tcp.port` (unless CallVault armed it itself), the ROM's `persist.adb.tcp.port`, the `tcp:`
  entries of `service.adb.listen_addrs` (`LoopbackPorts.alreadyOpenCandidates`). Never a guessed 5555: any app
  can bind that and get CallVault's key to sign its AUTH token.
  - Built-in mode: tried only **after** the Wireless-debugging connect has failed.
  - Offline mode: tried only **after** the loopback connect and its self-heal wait have failed, leaving out
    the ports that were just refused.
  - Skipped entirely until CallVault's key is authorised (`isAdbPaired()`), so Android's "Allow debugging?"
    prompt never appears because of it.
  - Each port is first asked with a plain `CNXN` (`AdbWireProbe`, 150 ms connect / 500 ms read; probing stops
    after 2 s). Only the FIRST port answering as classic adbd (`AUTH`/`CNXN`) gets a real connect (bounded 8 s);
    whole call ≤ ~10.7 s 📐 (`OpenAdbPortScan`).
  - Only "nothing answered as adb" is remembered (`OpenAdbPortGate`, in memory, 10 min or until the port list
    changes); an adb answer whose connect failed is retried next time. Nothing is persisted.
- New: `OpenAdbPortGate.kt`, `OpenAdbPortScan.kt`, and three functions in `LoopbackPorts.kt`.
- Small refactor: the settle/record-connected block shared by both connect paths is now `onConnected`.
- No preferences, no data, no migrations.

## Revert

```
git revert -m 1 c0a67b84
```

## What users would notice after a rollback

Almost everyone: nothing (no property names an adb tcp port on their phones, so nothing was dialled). On a phone whose ROM keeps adbd on tcp (some TV/OEM/eng builds), or where
another app (a Shizuku fork's TCP mode) left adbd on tcp while CallVault is in built-in mode, recording
would again need Wi-Fi + Wireless debugging whenever the normal path fails.

## Symptom that would justify rolling back

- Log shows `Connected over adbd's already-open tcp port :N` followed by recorder deaths / missed calls that
  did not happen before (a listener that does not stay up, e.g. one another app keeps moving).
- An unexpected Android "Allow USB debugging?" prompt for CallVault's key (should be impossible: the probe
  waits for `isAdbPaired()`).
- A slower recorder start on failure paths traced to `already-open adb tcp :N did not complete the ADB
  handshake within 8000ms` (a port that accepts but never answers; one connect per attempt, ≤ ~10.7 s).
