# Rollback: settings renamed to say what they do

🧪 VERIFYING — merged 2026-11-03; wording chosen by the maintainer from the settings-name audit (71 settings, 19
flagged). Seen on the OP9 (2.4.9-names1). Translations not yet read by native speakers.

Strings only (plus `summary_requirements_toggle_label`, a new key so the summaries switch no longer reuses the
dialog heading). Labels: Skip ignored contacts on app calls · Record app calls · Auto-record incoming/outgoing phone
calls · Skip hidden-number calls · Skip incoming/outgoing international calls · Skip all saved contacts / Record
everyone / Skip selected contacts · Incoming/Outgoing phone calls: who to record · Keep recording off Wi-Fi (desc:
Wi-Fi once, again after a restart) · Keep recording if the helper stops · Keep originals after merging · Vibrate on
actions · Scheduled · Allow mobile data usage · Show requirements · Show pop-up messages · Use system colours ·
Enable logging · sentence case for three. Cross-references updated; the resilient offer card points to Connection.
Also: Arabic `home_whatsnew_body` raw line breaks fixed.

```
git revert -m 1 33f53355
```
README impact: screenshots/text naming the old labels.
