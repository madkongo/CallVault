# Rolling back one item

Every backlog item is merged into `integration/next` as one merge commit, so it can be undone alone,
even after it shipped together with others.

    tools/rollback.sh <note-name>      # e.g. tools/rollback.sh classic-settings-removal

The script reads the merge commit from `<note-name>.md` here, reverts it as one new commit, keeps the
current wording of CHANGELOG/dev-notes if they conflict, and stops on any code conflict. Then add a
CHANGELOG line saying what was rolled back, build, test, release.

Each note says what changed, what users would notice after a rollback, and the symptom that would
justify one. Proven 2026-10-31: classic-settings-removal, decode-time-limit and urdu-transcription each
reverted cleanly on their own.
