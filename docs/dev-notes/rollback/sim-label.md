# Rollback: opt-in SIM label for each phone call

🧪 VERIFYING — merged 2026-11-03. 2399 unit tests pass (SimLabelsTest, PhoneCallMatchTest, BcrMetadataTest sim_slot,
settings registration). Not seen on a phone. To settle: on a dual-SIM phone, turn on Settings ▸ Sound and file
names ▸ "Show which SIM each call used"; one call on each SIM shows the SIM's name (or SIM 1 / SIM 2) in the list,
on the playback screen, and as `sim_slot` in the details file (if that is on).

## What changed
- `data/calls/`: `PhoneCall` + `CallKind`, `PhoneCallReader.since` (all call types, number, cached name, phone
  account), `PhoneCallMatch.nearest` (±2 min, answered calls only), `SimLabels` (name rule + per-pass resolver:
  PhoneAccountHandle → `TelephonyManager.getSubscriptionId` → `SubscriptionManager.getActiveSubscriptionInfo`).
- Name rule: the SIM's display name unless it is the carrier name or a default ("SIM1", "SIM 2", "Card 1", "eSIM");
  then "<sim_label_word> <slot+1>". Android hides whether a name was user-typed (name source is hidden API; OP9 shows
  displayName "SIM1", source CARRIER, carrier "Partner").
- `RecordingItem.simLabel` filled in `RecordingsRepository.listRecordings` (only when the pref is on; carrier calls only).
- Row subtitle, PlaybackScreen date line, `BcrMetadata.Input.simSlot` → `sim_slot`.
- Pref `show_sim_label` (default false, exportable), `SimLabelRow` in Sound and file names, strings × 15 locales.
- Not done: a `{sim}` file-name part — the name is fixed when recording starts, before the call log knows the SIM.

## How to roll back
```
git revert -m 1 49911fd9
```
(`49911fd9` = the merge of `feat/sim-label` into integration/next.) The pref key stays harmless if left in old exports.

## Follow-up 2026-11-03: name source + single SIM (merge 64e4c5bd, branch fix/sim-label-name-source) 🧪

Prior-art research (subagent, sources read): BCR gets the slot at call time (`Call.Details.accountHandle` →
`getSubscriptionId` → `simSlotIndex`, try/catch for SecurityException); Fossify Phone and the AOSP/Lineage Dialer
show `PhoneAccount.label` (built by Telephony from `SubscriptionInfo.displayName`, needs READ_PHONE_NUMBERS on
target 31+), and the Dialer shows nothing with ≤1 call-capable account. `PHONE_ACCOUNT_ID` is the ICCID on
Android 10–12 and the subId on 13+; `getSubscriptionId(handle)` only resolves SIMs that are active now.
Android 14+ prints `displayNameSource=` in the public `SubscriptionInfo.toString()`.
- Now: on 14+ `displayNameSource=USER_INPUT` decides; 11–13 keep the name-pattern guess. No label when fewer than
  two SIMs are active. Debug log `CV:SimLabels`.
- Testing: the standard emulator has one modem only; Cuttlefish (`--modem_simulator_count=2`) needs a Linux KVM
  host. A real second SIM is the practical test.
- Roll back this part alone: `git revert -m 1 64e4c5bd`.
