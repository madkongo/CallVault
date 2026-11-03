#!/usr/bin/env bash
# Undo one backlog item: reverts its merge into the current branch as a single new commit.
# Usage: tools/rollback.sh <rollback-note-name>     e.g. tools/rollback.sh classic-settings-removal
# The merge commit is read from docs/dev-notes/rollback/<name>.md. Text-only files (CHANGELOG,
# dev-notes) keep their current wording if they conflict; any conflict in code stops the rollback.
set -euo pipefail
name="${1:?usage: tools/rollback.sh <rollback-note-name> (see docs/dev-notes/rollback/)}"
note="docs/dev-notes/rollback/${name}.md"
[ -f "$note" ] || { echo "No rollback note $note"; exit 1; }
sha=$(grep -o 'git revert -m 1 [0-9a-f]\{7,40\}' "$note" | head -1 | awk '{print $5}')
[ -n "$sha" ] || { echo "No merge commit found in $note"; exit 1; }
[ -z "$(git status --porcelain --untracked-files=no)" ] || { echo "Commit or stash your changes first."; exit 1; }
echo "Reverting $name (merge $sha)…"
if ! git revert -m 1 --no-commit "$sha"; then
  for f in $(git diff --name-only --diff-filter=U); do
    case "$f" in
      CHANGELOG.md|docs/*) git checkout --ours -- "$f"; git add "$f" ;;
      *) echo "Code conflict in $f — resolve by hand, or 'git revert --abort'."; exit 2 ;;
    esac
  done
fi
git commit -q -m "revert: roll back ${name} (merge ${sha})" -m "Rolled back with tools/rollback.sh. Add a CHANGELOG line saying it was rolled back."
echo "Done: $(git log --oneline -1). Next: add a CHANGELOG line, build and test."
