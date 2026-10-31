# Rollback: `feat/open-adb-port-probe`

🧪 VERIFYING — built 2026-10-31, unit tests pass, **not run on a phone** (and none of our phones has a ROM
that opens adbd's tcp port by itself, so the success path is unproven anywhere).

## What changed

- `AdbShell.ensureConnected` gained one last-resort transport, `connectAlreadyOpenPort`: a tcp port adbd was
  **already** listening on without anyone arming it — `service.adb.tcp.port`, the ROM's `persist.adb.tcp.port`,
  the `tcp:` entries of `service.adb.listen_addrs`, then 5555 (`LoopbackPorts.alreadyOpenCandidates`).
  - Built-in mode: tried only **after** the Wireless-debugging connect has failed.
  - Offline mode: tried only **after** the loopback connect and its self-heal wait have failed, leaving out
    the ports that were just refused.
  - Each port is first asked with a plain `CNXN` (`AdbWireProbe`, 150 ms connect / 500 ms read); only a port
    answering as classic adbd (`AUTH`/`CNXN`) gets a real connect (bounded 8 s).
  - A miss is remembered in memory (`OpenAdbPortGate`) until adbd's tcp ports change; nothing is persisted.
- New: `OpenAdbPortGate.kt`, and three functions + one constant in `LoopbackPorts.kt`.
- Small refactor: the settle/record-connected block shared by both connect paths is now `onConnected`.
- No preferences, no data, no migrations.

## Revert

```
git revert -m 1 MERGE_SHA
```

## What users would notice after a rollback

Almost everyone: nothing (their phones have no already-open adb tcp port, and the probe ran only after
the normal path had failed). On a phone whose ROM keeps adbd on tcp (some TV/OEM/eng builds), or where
another app (a Shizuku fork's TCP mode) left adbd on tcp while CallVault is in built-in mode, recording
would again need Wi-Fi + Wireless debugging whenever the normal path fails.

## Symptom that would justify rolling back

- Log shows `Connected over adbd's already-open tcp port :N` followed by recorder deaths / missed calls that
  did not happen before (a listener that does not stay up, e.g. one another app keeps moving).
- An unexpected Android "Allow USB debugging?" prompt for CallVault's key appearing after a failed
  Wireless-debugging connect, which users find confusing.
- A slower recorder start on failure paths traced to `already-open adb tcp :N did not complete the ADB
  handshake within 8000ms` (a port that accepts but never answers; capped once per port list per process).
