# i18n Fix Plan — Complete the Translations

## Problem (root cause)

The app resolves locale purely from resources (AndroidX per-app language via
`AppCompatDelegate.setApplicationLocales` + `generateLocaleConfig = true`). There is **no code
bug**. The defect is missing translated string resources: when a key is absent from a
`values-<locale>/` folder, Android falls back to the default English `values/strings.xml` for
that key.

The reported symptom ("first page stays English, Settings is translated" for a French user) is
this fallback hitting the Disclaimer screen's text — which lives in
`strings_onboarding_ui.xml`, a file that was never localized in any locale.

## Scope of the gap

- **289** translatable keys in the default `values/` (309 total − 20 `translatable="false"`).
- The 20 `translatable="false"` keys (`app_name`, audio codec/source labels, `disclaimer_body`
  legal text, fork attribution) intentionally stay English — **do not translate them**.
- The default split files and their universal gaps (missing in **every** locale):
  `strings.xml` (101 newer keys), `strings_home_filters.xml` (29),
  `strings_onboarding_ui.xml` (23), `strings_wizard_ui.xml` (10), `strings_home.xml` (8),
  `strings_settings_ui.xml` (4).

### Missing-key count per locale

| Locale | Missing |
|---|---|
| values-it | 250 |
| values-de | 189 |
| values-ru | 185 |
| values-fr | 175 |
| values-es | 175 |
| values-hu | 175 |
| values-pl | 175 |
| values-vi | 175 |
| values-zh-rCN | 175 |

Total ≈ 1,674 individual translations. No orphaned/stale keys exist (nothing to delete).

## Decisions (locked)

- **Translations produced by:** Claude, all 9 locales.
- **File layout:** mirror the default split — each locale gets `strings.xml`,
  `strings_home.xml`, `strings_home_filters.xml`, `strings_onboarding_ui.xml`,
  `strings_settings_ui.xml`, `strings_wizard_ui.xml`. Makes future drift diffable file-by-file.
- **Coverage:** all 9 shipped locales to 100%.

## Constraints / correctness rules

1. **Preserve format args** exactly (`%1$s`, `%d`, `%s`, …) — 15 default strings contain them;
   reorder text but never drop/renumber a placeholder.
2. **Escape XML/Android specials** in translated values: `'` → `\'`, `"` → `\"`, leading `@`/`?`,
   `&` → `&amp;`, `<`/`>`. (Existing French escapes apostrophes correctly — match that.)
3. **Never emit `translatable="false"` keys** into locale files.
4. **Keep the exact key names** from default; do not invent or rename keys.
5. Preserve any inline markup / `\n` / `%%` literally.

## Execution (subagent-driven, one agent per locale)

Worklist manifests already generated at
`scratchpad/i18n/<locale>.missing.tsv` (columns: source-file ⇥ key).

- **Phase 0 — Safety net.** Add a Gradle `lint` gate: set `MissingTranslation` and
  `ExtraTranslation` to `error` (`app/build.gradle.kts` lint block) so regressions fail the
  build. Snapshot current `values-*` for rollback.
- **Phase 1 — Source of truth.** Emit English text for the 289 translatable keys keyed by
  source file (done as worklist; English values pulled from `values/`).
- **Phase 2 — Translate (parallel, per locale).** One subagent per locale translates only its
  missing keys, obeying the constraints above. Native-quality target for fr/es/de/it/pt-style
  majors; best-effort for vi/zh/hu/pl/ru with format-arg fidelity enforced.
- **Phase 3 — Write files.** For each locale, write/extend the 6 split files with the new
  `<string>` entries (alphabetical-by-key within each file for stable diffs). Leave existing
  translated entries untouched.
- **Phase 4 — Verify.**
  - `./gradlew :app:lintDebug` → expect 0 `MissingTranslation` for the 9 locales.
  - Placeholder-consistency script: assert each translated value's `%…` set matches default.
  - Build `assembleDebug`.
  - Render smoke-check: launch in French, confirm Disclaimer hero/body/scroll-hint render in FR.

## Acceptance criteria

- All 9 locales report 0 missing translatable keys (289/289 each).
- `lint` passes with `MissingTranslation`/`ExtraTranslation` as errors.
- No `translatable="false"` key appears in any locale file.
- First page (Disclaimer) renders fully in the system language for fr (+ spot-check de, it).
- Debug build compiles and installs.
