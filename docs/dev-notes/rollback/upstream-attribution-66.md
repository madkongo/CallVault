# Rollback: upstream attribution fixes (#66)

🧪 VERIFYING — built on `fix/upstream-attribution-66` 2026-11-01; unit tests (2344) and checkTranslations pass.
Support dialog seen on the OP9 2026-11-01 (2.4.8-next5): new title and English text shown correctly (Claude-run).
The welcome screen has not been seen on a phone. To settle:
- **Support dialog:** Settings ▸ Support development. Check the new title and text, in English and one other language.
- **Welcome screen:** it only shows on first launch. Check it on a fresh install (emulator), or in the Compose preview of `DisclaimerScreen`.
  - The fork line and both copyright lines show.
  - Tapping the GitHub link opens the browser.

## Why

kitsumed (ShizuCallRecorder's author) filed #66 on 2026-10-31. GPLv3 §4 says keep copyright notices intact, and the
upstream license's §7 terms 3 and 4 ask for a prominent fork notice with a link. Three things broke that:
- `disclaimer_body` replaced "Copyright © 2026 Kitsumed (Med)" with ours.
- The support dialog said "Made by one person" / "I build CallVault on my own".
- Commit e7249f4a replaced kitsumed's copyright line in the headers of files vendored at f28c7d41.

## What changed

- **Headers (30c57365):** 53 files get `Copyright (C) 2026-present kitsumed (Med)` back, above our line.
  - 52 were traced to f28c7d41 with `git log --follow`.
  - `FileNameFormatDialog.kt` lost its history in the package rename (494c1598) and was added by hand.
  - `StorageTarget.kt`, `SyncScheduleMode.kt` and `theme/Shape.kt` were false rename matches. They are ours, so their headers are left as they were.
  - The gradle `.properties` files never lost kitsumed's line.
- **Welcome screen (5cb25093):**
  - `disclaimer_body` (English only, `translatable="false"`) gains the §7 fork sentence and kitsumed's copyright.
  - New `ui/common/LinkifyUrls.kt` makes http(s) URLs in the disclaimer tappable; the Wikipedia link becomes tappable too.
  - Test: `LinkifyUrlsTest`.
- **Support dialog (a53e581c):** `support_appeal_title` and `support_appeal_body` are rewritten in all 15 languages to name ShizuCallRecorder by kitsumed.
- No preference, database or file-format change.

## How to roll back

```
git revert -m 1 054b941f
```

(`054b941f` = the merge into integration/next.) Rolling back puts the license problem from #66 back. Only do it
together with another fix for that issue.

## Pushed for kitsumed (2026-11-01)

The same three commits were cherry-picked onto `origin/main` (2.4.8) and pushed as branch
`fix/upstream-attribution-66-main`: 9490cf9d, e9369ab0, a12cef88. 2284 unit tests and checkTranslations pass there.
It was not merged into main. The rest of integration/next stays unpushed.
