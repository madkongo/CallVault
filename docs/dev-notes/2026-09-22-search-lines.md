# 2026-09-22 — a search line at the head of Recordings, Transcripts and Summaries

Status: **🧪 VERIFYING** — branch `feat/search-lines` (`7d0d0896`), 1753 unit tests green, installed on
the OP9 and driven over adb by the assistant (screenshots below), NOT yet used by the maintainer. To settle:
on your phone, type a contact's name on Recordings and pick it from the menu; type a Hebrew word on
Transcripts and on Summaries and read the excerpt under each row.

## The request

Maintainer: a search line on each of the three library pages — Recordings between the header and the
filter chips, searching the contact name with an autocomplete dropdown; Transcripts between the header
and the import card, and Summaries between the header and the list, both searching words within the text.
Decided in the design round: matches show as a filtered list with an excerpt under each row (the match in
bold); the Recordings completions come from the recordings themselves, not the phone's contact book; the
old header magnifier and its bottom sheet go; the magnifier sits inside the field.

## What was already there

- Recordings had a **Contact** filter chip over the same data (`contactFilter`/`availableContacts`). The
  search line is that facet typed instead of scrolled, so the chip is gone rather than duplicated.
- Transcripts and Summaries both had **FTS4 tables** (`transcript_segments_fts`, `call_summaries_fts`,
  `unicode61`, no stemming — a Hebrew-driven choice) and DAO `search(query)` calls, used only by the
  bottom sheet (`TranscriptSearchSheet`, deleted). The sheet also searched notes; nothing does now.

## How it is built

- `ui/common/SearchLine.kt` — one composable for the three pages: `OutlinedTextField`, magnifier as the
  leading icon, × once non-empty, and an `ExposedDropdownMenu` on an *editable* anchor when suggestions
  are given (`M3DropdownField` is read-only, so this is a sibling).
- Recordings: `HomeUiState.contactQuery` replaces `contactFilter`; `matchesContact` = contact key (name,
  else number, else file name) contains the query; `contactSuggestions` = saved **names** only, that
  contain the query, A→Z, at most 8, none once the query is a name. Pure state, `HomeContactSearchTest`.
- Transcripts / Summaries: `TranscriptRepository.searchPage(index, query)` → `Map<displayName, Excerpt>`,
  one index per page; `rememberPageSearch` (250 ms debounce, `rememberSaveable` query, re-run when the
  page's entries change); `Groups.matching(names)` keeps only the ready rows named; `LibraryNameRow`
  gained an `excerpt` line, mirrored to the excerpt's own direction, match in bold.
- `PageSearch` (pure): `matchExpression(query, prefixLast)` and `excerpt(text, query, radius)`.

## Measured

**FTS4 prefix syntax, on sqlite3:** `"בדי"*` matches nothing; bare `בדי*` matches; `"hello" wor*` matches;
`"hello" "wor"*` and `"hello wor"*` do not. So the last word goes unquoted with `*` — but only when it is
letters and digits alone, since unquoted punctuation is an operator and one such crash was already paid for.

**On the OP9 (11:00):** Transcripts, "plus" → 2 of 4 rows, each with `בדיקה, בדיקה, זה 1 12 **plus**.` under
it, RTL. Recordings, "2026" → the menu listed WhatsApp *file names* as completions — nameless recordings are
keyed by file name — fixed the same minute: completions are saved names only; typing still finds the rest.

## Not measured

The maintainer's own use; the menu with a Hebrew name in it (adb cannot type Hebrew); Summaries with any
summaries in them (the OP9 has none); a library of thousands (the contact filter is a linear scan per
keystroke over the in-memory list, which is what the chips already did).
