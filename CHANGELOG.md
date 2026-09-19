# Changelog

All notable changes to CallVault are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/), and this project uses semantic-ish versioning.

## [2.4.0] — unreleased

### Added

- **A home screen, with Recordings, Transcripts and Summaries as their own places.** Transcripts and
  summaries used to be things that happened to a recording; now each has a page you can open, and the
  app reopens wherever you were last.

- **Audio can be brought in from outside.** Pick a file from the Transcripts page, or share one to
  CallVault from any other app — a WhatsApp voice note, for instance, which no file picker can see.
  CallVault asks what you want it for:

  - **Import and transcribe** keeps the audio and adds it alongside your calls.
  - **Transcribe only** gives you the words and deletes the audio once the transcript is safely saved.

- **A transcript has a page of its own to be read on**, with a note you can write against it, and a
  badge on the row when the words are all that is left.

- **Row menus and multi-select.** Every transcript and summary has a ⋮ menu — delete, share, save — and
  a long press picks several at once.

- **A notification when a transcription finishes**, and one when it does not.

- **A way to join the Telegram group from the home screen**, for anyone who would rather ask than open
  an issue.

### Fixed

- **Restarting the phone could leave it not recording, silently and for good.** Recovery decided it
  could not act, and the one thing that would have let it act was the thing it was refusing to do.
  Reported by nobody — it was found on the maintainer's own phone, and it had been there for releases.

- **Setup could not be completed on Android 17.** Android 17 stopped telling apps whether Developer
  options and USB debugging are on, so CallVault believed they were off and sent people to a settings
  screen they had already visited, with no way forward. It now works this out for itself instead of
  asking Android. Existing users on Android 17 were also told, wrongly, that their setup was broken
  and that calls had not been recorded when they had. Reported by teou1 and siongui in #40.

- **Shizuku is started again when CallVault has to stop it.** CallVault sometimes restarts Android's
  debugging service, which takes any running Shizuku server down with it — including one other apps
  were using. It now puts it back, and says so. Reported by johnwick113 in #39.

- **The "CallVault cannot record right now" warning now goes away by itself** once recording is
  working again, instead of staying until the next restart.

- **The USB debugging switch in Settings no longer flips itself back off** after you turn it on.

## [2.3.0] — 2026-09-11

### Added

- **Calls that were really one conversation can be merged into one recording.** A call drops and you
  ring each other back; to you that was one conversation, but it arrives as two recordings. Open the
  ⋮ menu on the call that started it, choose *Merge with another call*, and tick the ones that
  continued it — they join in the order you tick them, numbered as you go, so you can see the order
  before committing to it. It works for any number of calls, and a merged call can be merged again.

  The join is lossless and takes a moment: nothing is re-encoded, so the merged recording contains
  the original audio exactly. The transcript, marks, tags, star and note come across with it, timed
  to the merged recording, and a mark is dropped at each seam so the joins are somewhere you can
  jump to.

- **And they can be un-merged again**, from the same menu, getting every original call back with the
  transcript and marks it had. This works because the merged recording physically contains the
  originals, so taking it apart is an exact cut rather than a reconstruction — which is what makes it
  safe for a merge to leave you with one recording instead of two copies of everything.

  By default the calls a merge was made from are removed, and the dialog says so before you confirm.
  *Settings ▸ Storage ▸ Keep the original calls* keeps them on the phone as well; un-merging works
  the same either way.

- **A bug report can be saved as a file.** *Share* now has *Save* next to it, which opens Android's own
  save dialog so you choose the folder and the name. Both halves of the report go into one text file.
  The report used to exist only in CallVault's private storage, where no file manager — and no
  `adb pull` — can reach it, so the only way to get it off the phone was to send it through another
  app. Asked for by mirror176 in #28 and #29.

### Changed

- **The first-run notice no longer holds you back with a timer.** Continue used to stay disabled for
  five seconds whether or not you had read anything. A forced wait does not make anyone read; it
  makes them wait, and it charged the time again on every reinstall and every phone someone set up.
  The notice still has to be scrolled to the end and acknowledged — both of which are the reader's
  own doing. Reported by mirror176 in #27.

- **What's New says one line per change instead of several paragraphs.** The newest release is
  written out as a scannable list, releases you skipped keep their headline so you can see you
  missed them, and a link goes to the full release notes on GitHub, where being thorough costs
  nobody anything. Reported by mirror176 in #27, who had to "parse through" the 2.3.0 note.

- **A journal of the first setup is now always kept, and can be shared.** The debug log is off until
  you switch it on, so the first minutes after an install — when setup either works or does not —
  were recorded nowhere, and by the time anyone asks for a log the moment has passed. This one is
  small, narrow and always there: only the steps that get recording working (finding the ADB service,
  connecting to it, starting the recorder, the grants and the modes), never anything about a call,
  its audio or its text. It is capped, it stops once a recorder has connected, and *Settings ▸ Debug*
  can read, share and delete it whether or not logging was ever switched on.

### Fixed

- **A transcription you started no longer posts a "your phone may get warm" notification.** You had
  just confirmed a dialog saying the same thing. Automatic transcriptions — nightly, or after a call —
  still show it, since nothing else tells you they are running. A run you started that is expected to
  take eight minutes or more keeps it too: without it Android may stop the job at ten minutes, and a
  stopped transcription starts again from nothing. Reported by mirror176 in #31.

- **Automatically recorded calls no longer flash "Press to start recording".** The recording
  notification was posted before the service had acted on the call, so for a moment it offered a
  Record button for a call that was already being recorded — and did the same again as the recording
  ended. Recording was never affected. Reported by mirror176 in #31.

- **A recorded call shows one notification instead of two.** While a phone call was being recorded, "Ready
  to record calls" stayed in the shade next to the recording notification — saying the app was ready to record
  while it was already recording. The recording notification now takes its place for the length of the call,
  and "Ready to record calls" comes back when it ends. Reported by the maintainer while checking #31.

- **Transcription progress no longer stalls at about three quarters and then jumps to the end.** The
  figure between whisper's own reports was drawn on a curve that read 75% at the moment the job was
  expected to finish, so a quarter of the bar was never used. It now follows the clock to about 90% by
  the expected finish, and keeps creeping if the job runs long. Summaries get the same fix. Reported by
  mirror176 in #33.

- **Rotating the phone no longer looks like it restarts a transcription.** The percentage is
  predicted between whisper's own reports, and the clock behind that prediction lived on the screen
  showing it — so turning the phone threw it away and the figure fell back to 1% and climbed again.
  The transcription itself was never affected and always finished on time, but there was no way to
  tell that from the outside. The figure now belongs to the run producing it, and a rotation cannot
  touch it. Reported by mirror176 in #34.

- **Deleting a call now stops its transcription.** The run used to carry on to the end, producing a
  transcript of a recording that no longer existed — and because one transcription runs at a time,
  everything queued behind it waited for a result that was thrown away. Deleting the recording now
  ends its run, and the queue moves straight on. Reported by mirror176 in #35.

- **The microphone indicator no longer stays on after a call.** When Android tears a capture down
  mid-call, CallVault rebuilds it and the recording continues whole — but Android can leave the torn-down
  capture's microphone marker running, so the green dot stayed lit with nothing recording. Stopping the old
  capture turned out not to clear it; replacing the background recorder does. A few seconds after a call ends,
  CallVault now checks for exactly that and, if nothing is recording and the recorder can be brought straight
  back, replaces it. It never does this during a call, and if the recorder could not come back it leaves the dot
  rather than risk the next call.


- **CallVault no longer switches off a debugging switch you turned on yourself.** Wireless debugging
  that CallVault enabled is still switched off when it is done with it — but a switch you flipped is
  yours, and it is left alone. It used to be taken away within a second, with a note suggesting you
  disable USB debugging instead, which costs you the thing you wanted. Reported by mirror176 in #30,
  who uses it to reach his phone from a PC.

- **Turning on off-Wi-Fi recording says what actually failed.** Every failure produced the same
  sentence — "connect to Wi-Fi once, then try from Settings" — including on a phone already on Wi-Fi
  with Wireless debugging on. It now distinguishes Wireless debugging being off, the phone's debugging
  service being unreachable, and the local port not coming back. Reported by mirror176 in #30.


- **A bug report can be shared without switching logging on first.** Share was only offered once a log
  file existed, so reporting anything meant enabling logging, reproducing, disabling it again — and the
  most useful half of a report needs none of that. The report always carries the configuration header
  (mode, transport, which switches are on, whether the recorder is connected) and the setup journal
  when there is one, so it is now offered whenever logging is off.

- **The log viewer can actually be read.** It was a small popup that cut every line off at the right
  edge, gave no sign it could scroll, opened at the oldest lines and closed when the phone was turned.
  It now fills the screen, wraps long lines — a switch turns that off for one line per row with
  sideways scrolling — shows scrollbars, opens at the newest lines and stays open when you rotate.


- **The transcript no longer changes size as you read it.** It opened at about half the screen with
  the playback controls out of sight, grew as you scrolled, collapsed again when the phone was turned
  and opened full the next time — because its height followed however much of the text had been drawn
  so far. It is now a stable near-full sheet with the controls where you left them. Reported by
  mirror176 in #27.

- **Cancelling a transcript deletion puts the transcript back.** The sheet closes before the
  confirmation so the dialog is not sitting on top of the text it is asking about, but saying no then
  left you back at the list. Reported by mirror176 in #27.


- **Updating the app no longer leaves it unable to record the next call.** Installing an update stops
  CallVault's background services and the privileged recorder that captures the audio, and until now
  they were only brought back if the update had also cost the app a permission. If it had not — the
  usual case — nothing restarted them, and a call arriving in that window woke the app, started a
  recording and produced no file. Found the hard way: a thirteen-minute call, eight minutes after an
  update, with the call right after it recording perfectly.

- **A line spoken after a long pause is no longer timestamped as if it came before it.** Tapping a
  line in a transcript jumps to that moment in the call — except after a silence, where it could land
  more than ten seconds early, on the wrong speaker. Reported by a user who noticed it on a call that
  was transferred, with a long hold in between.

  The cause is in the speech detection: it removes the silence before transcribing, and every pause,
  however long, was being treated as a tenth of a second when the times were mapped back. Times are now
  put back against the speech that was actually there. Checked against the recording the reporter
  attached: the line that was stamped at 1:16 is now at 1:28, where it is really said.

- **Playing a voicemail no longer counts as a call that failed to record.** CallVault spots app calls
  by the phone switching its audio into "communication" mode, because Android offers nothing better —
  but a voicemail app takes that same mode to play through the earpiece. Every voicemail played, and
  every resume after a pause, was announced as an app call that had gone unrecorded, and was written
  into the app's health record as a missed call. Reported by a user who found the warnings only stopped
  when he switched to Shizuku, which turns app-call recording off entirely.

  CallVault now checks whether anything on the phone is actually capturing audio for a call before it
  says a call was missed. It never withholds a recording on that basis — only the warning — so a call
  can still be recorded even where the check cannot confirm it.

- **An app you have switched off for recording can no longer produce a "call not recorded" warning.**
  The per-app choice was consulted after two of the checks that raise it, so an excluded app still
  warned you whenever the recorder was not ready or the folder was unwritable.

- **The crackling in call recordings is fixed, and both causes of it are.** On phones that could not
  quite keep up, the recorder threw away a 21-millisecond fragment of the call and joined the audio
  either side of it — silence sounded fine, but a cut through the middle of a word became a click.
  Reported by someone who noticed it only when there was speech. Fragments are no longer dropped, and
  the part of the recorder that reads the microphone now runs on its own, so nothing else being slow
  can starve it. If a phone still loses audio, the recording says how much rather than hiding it.

- **Switching the recording format to AAC no longer costs you the next call.** Choosing a format in
  the setup wizard kept the previous format's bit rate, which some phones' encoders refuse outright —
  and a refusal meant a call that recorded nothing at all, silently. The format now brings a workable
  bit rate with it wherever you choose it, and if a phone still refuses, the recorder tries again at
  the rate that format is meant for instead of giving up.

- **A recording that never starts now says so.** The recorder could accept a call, fail to open the
  microphone, and leave you with nothing — no error, no file, nothing in the log. It now checks that
  capture really began and tells you when it did not.

- **CallVault no longer recommends a USB setting that stops Shizuku.** Changing the phone's Default
  USB configuration restarts Android's debugging service, and Shizuku's server stops with it — so the
  advice that makes recording more reliable for everyone else broke the setup of anyone using Shizuku,
  as one of them reported. That advice, and the USB debugging switch beside it, are now explained
  rather than recommended in Shizuku mode. The setting is also never changed during a call, where
  applying it would have ended the recording in progress.

- **The list keeps your place.** Scrolling a long way down, opening a recording and coming back put
  you at the top of the list again. A **Top** button also appears once you have scrolled far enough
  to want it.

- **A recording's Drive badge appears when the copy lands**, instead of on the next launch. Copying
  to Drive happens in the background — after a call, or after a merge — and the list only read the
  catalogue once, so a recording could sit there looking device-only for as long as the app stayed
  open.

- **Transcription no longer says a two-minute call will take three hours.** The estimate is learned
  from finished runs, and a check meant to reject an impossible reading let it through on any phone
  that had not measured one yet — so a single bad reading became that phone's permanent opinion of
  itself and was quoted back for the next dozen runs.

  The way a run was modelled was wrong underneath that, too. It was treated as purely proportional to
  the length of the call, when a run also loads an 874 MB model before it looks at any audio, and
  whisper does a full 30 seconds of work on anything shorter than 30 seconds. A ten-second clip
  therefore measured as a phone six times slower than it is. Both parts are now measured separately,
  so a short clip and a long call agree about how fast the phone is.

  Until a phone has timed one run, the confirmation now says the first one may take a while rather
  than quoting a figure measured on somebody else's hardware. Estimates already stored are discarded
  once, because they were measuring a different thing; the next run replaces them.

  The quoted time is also rounded up rather than down. Truncating threw away up to 59 seconds on
  every estimate, on top of an estimate that already aims at the middle — so it was biased short
  twice over, and a run that overshoots what it promised reads as a hang.

- **Turning the phone no longer throws away what you were doing.** Rotating closed the recording you
  had open and dropped you back at the top of the list — reported by someone who rotates deliberately,
  to read a longer transcript and get a wider waveform. The same thing quietly cleared a multi-selection
  part-way through choosing, closed the transcript sheet and its search, and dismissed whichever dialog
  was open, including the bulk-delete one after you had picked which copies to remove. All of it now
  survives the turn, and a recording that was playing keeps playing on the screen it belongs to.

- **The app lock no longer asks again every time you rotate.** Turning the phone counted as leaving the
  app, so it demanded a fingerprint each time — while a phone that flips between angles in your hand
  could ask several times over. It now tells a rotation apart from actually leaving: backgrounding the
  app, or closing it from recents, still locks it exactly as before.

- **The unlock screen no longer flashes on every open.** A card reading "locked", with an Unlock button,
  was drawn for a moment before the fingerprint prompt appeared and again as it disappeared. That card
  exists only so a prompt dismissed by accident does not leave force-stopping the app as the only way
  back in, and it now appears only in that case.

## [2.2.1] — never released on its own; everything here ships in 2.3.0

A maintenance release built entirely from what people reported after 2.2.0 went out. Nothing here is
a new capability; all of it is something that was wrong, or something CallVault could not tell you.

### Fixed

- **Opening the app is fast again, and stays fast as your library grows.** 2.2.0 re-read the length of
  every recording the call log could not answer for, every single time the list was drawn — one file
  open and one media parse each, on the main path. With a few dozen calls the list took long enough
  that a recording made minutes earlier looked as though it had never happened, and the delay grew
  with the size of the library rather than staying constant. A recording's length is now read once
  and remembered, so a list that has been drawn before comes back immediately.

- **CallVault no longer says Drive backup has stopped when it plainly has not.** Two people were
  warned that their recordings were not reaching Google Drive while every one of them was arriving
  correctly. The sweep skipped over a recording whose Drive copy was already there without recording
  that it had seen it, and the health check then read that silence as age, and age as failure. A copy
  that is present is now stamped as present, and the warning needs evidence about the present rather
  than an absence of recent news.

- **Three places that were still in English regardless of your language.** The headings inside an
  exported transcript, the count of selected apps, and the back button's description now follow the
  app's language like everything else.

### Added

- **The debug report now says who is holding the microphone.** If the phone shows the green microphone
  dot when no call is happening, the report lists every audio capture the system currently has open,
  which application each belongs to, and whether it was ever closed — and separately counts CallVault's
  own privileged recorder processes, of which exactly one should ever be running. This exists to settle
  a problem that has so far only been reproducible on someone else's phone.

### Documentation

- **The setup wall on OPPO, OnePlus and Realme phones is written down.** On ColorOS and OxygenOS the
  developer option CallVault needs is called *Disable system optimization* rather than the name every
  other guide uses, and it is invisible unless the phone's language is set to English — so people
  reasonably concluded the option had been removed and that CallVault simply did not work on their
  phone. The README now names both wordings, says where the option hides, and notes that it applies to
  Shizuku mode too.

- **The in-app "What's new" note had no 2.2.0 entry**, so the largest release the app has had announced
  itself with the note from the release before it.

## [2.2.0]

A release about the parts of using a call recorder that are not the recording: finding a call again,
controlling one while it is happening, and being told when something has quietly stopped working.

### Added

- **Mark a moment while you are talking.** A button on the call notification drops a bookmark at the
  point you press it; the recording's page shows them as chips that play from there. It works on
  phone calls and app calls alike, and the marks are positions in the saved audio, so they stay
  correct even if you paused.

- **App calls have controls at all.** Until now a WhatsApp or Signal recording in progress could be
  started and finished with nothing to stop it. It now carries its own notification with pause,
  resume, stop and mark — the same ones a phone call has.

- **An app call interrupted by a phone call stays one recording.** Take a cell call in the middle of
  a WhatsApp call and come back, and it used to arrive as two separate files. The app recording is
  now held open across the phone call and continues into the same one. The microphone is genuinely
  released for the phone call — it has to be, or the phone call's own recording suffers — so the held
  stretch is absent rather than recorded.

- **Choose which apps get recorded.** Every app is on by default; switch one off and its calls are
  never recorded. Useful where consent law differs, or for an app you simply would rather leave alone.

- **Stars and tags.** Star the calls worth keeping and filter the list to them; label calls with your
  own words and filter by those. A tag can be renamed or removed everywhere at once, which is the
  only way to fix a typo already applied to forty calls.

- **Export a transcript** as plain text, Markdown, SRT, VTT or JSON. The subtitle formats mean a
  recording and its transcript can be opened together in any video player.

- **Search covers summaries and notes**, not only the spoken words. A call is now findable by what its
  summary concluded, or by something you wrote about it afterwards.

- **An optional app lock**, using your phone's own unlock, before any recording or transcript is shown.

- **Housekeeping that is not only about age.** Alongside deleting recordings past a chosen age, you can
  now cap how much space they may take, or discard very short ones — a misdial or a call that rang
  out. All off by default, and a starred recording is never deleted automatically, even if that means
  the cap is not met.

- **CallVault says when it has stopped working.** If recording cannot start after a reboot, or
  recordings stop reaching Google Drive, you are told. Both of those used to be silent, and the way
  you found out was a call that was not there.

- **An optional BCR-compatible details file** beside each recording, so tools built for BCR — such as
  bcr-gui — can read your calls' number, contact and direction. Off by default.

- **Long calls can be transcribed.** They are decoded and transcribed in passes rather than all at
  once, which is what used to exhaust memory on anything long.

### Changed

- **One Share button in a transcript**, offering plain text or a file, instead of separate Copy,
  Share and Export. Long-press a line to copy just that sentence; a single tap still plays from it.

- **The summary model is smaller** — 2.62 GB instead of 3.46 GB — by using the quantisation-aware
  build rather than a plain quantisation of the full model. Same model, fewer gigabytes to download
  and to hold in memory.

- **Offline recording states its limitation up front.** A reboot clears it, and it returns as soon as
  the phone joins any Wi-Fi network for a few seconds. It does not need internet, and any access point
  will do — wording that used to make a ten-second fix sound like a trip home.

### Fixed

- **Nothing appears in your storage folder until a recording is complete.** Syncthing, FolderSync and
  Nextcloud upload whatever they find, so a file that grew during the call was being backed up
  truncated. Recordings are now written privately and published whole.

- **An app call no longer loses its speaker separation.** The capture had both sides apart all along
  and was discarding it at the downmix, so app calls arrived with no speaker labels while phone calls
  had them.

- **A transcript with malformed text no longer takes the app down.**

- **Transcription and summarisation no longer stop when the screen goes off.**

- **Call details survive on Android 13.**

## [2.1.2]

Fixes a transcription regression introduced in 2.1.1.

### Fixed

- **The opening of a call could be lost.** 2.1.1 changed the transcriber to weigh several candidate
  readings rather than take the first. On a call that begins with music or a tone, that change
  combined badly with the new silence-skipping: the first minute and a half of a real call came back
  as nothing at all, and one word could repeat dozens of times in a single line. Weighing candidates
  has been switched back off. Silence-skipping stays — on its own it produced the most complete
  transcript of everything tested, repaired two places where the old version had got stuck repeating
  itself, and is faster than 2.1.1 was.

## [2.1.1]

Transcription and summaries got faster and less wrong, without a bigger model.

### Changed

- **Transcription is faster than it was, while doing more work.** Silence is now skipped before the
  model ever sees it, and the decoder considers several candidate transcriptions rather than taking
  the first word that comes to mind. Measured on a real four-minute call: 362 s before, 358 s after —
  and the new one recovered a whole sentence the old one had simply dropped.

- **Summaries stop repeating themselves.** The output format allowed a list to go on for ever, and the
  instruction to keep it to five items was only ever a request. It is now a limit the model cannot
  exceed, which is what was producing "four decisions, three of them the same sentence".

- **Summaries say more.** Items were capped at five and each one asked to be a single short line; both
  of those were being obeyed faithfully, against the content. The cap is now eight and the length
  limit is gone, with the budget raised first so a long answer in Hebrew or Arabic does not run out of
  room.

### Fixed

- **A long summary is no longer thrown away.** An answer that ran out of room used to be discarded
  whole, which was most likely in the languages that need the most words. What arrived is now kept.

- **The instructions written for the summariser were not the ones it was being given.** Four of them —
  that the transcript is imperfect, to write in its own words, to cover the whole text, and to ignore
  what is clearly mis-transcribed — had been written after real failures and applied to a code path
  that no longer ran. A transcript arriving as hundreds of fragments is now joined into readable prose
  first, which is what those failures were about.

- **Recordings were being quietly degraded on the way into the transcriber.** Reducing the audio to the
  rate the model wants was dropping samples without filtering first, folding high frequencies back
  into the speech band as noise. Outgoing calls were worst affected, because they carry the most of
  your own microphone.

## [2.1.0]

Transcripts learn who was speaking, and CallVault can run on Shizuku instead of its own helper.

### Added

- **A speech model that is both better and about twice as fast.** The Best quality tier is now an
  874 MB model rather than a 574 MB one — and, despite being the larger download, it transcribes a
  call in roughly half the time. The reason is not the model but the maths library underneath it,
  which has a fast path for this model's number format and none at all for the old one. A ten-minute
  call that took about twenty-two minutes takes about eleven.

  The old model has not gone anywhere. It is still offered, as **Best quality, smaller download**,
  and stays the one CallVault starts with, so nothing you have already downloaded stops working or
  needs fetching again. Switching is a choice, in Settings ▸ Transcription &amp; summaries: 300 MB more
  to download and a little more memory while it runs, against half the waiting, every call.

- **Run on Shizuku instead, if you already have it.** CallVault has always started its own
  shell-level helper over ADB, which is why it needs nothing else installed — but it also means
  leaving a debugging switch on. If you already run [Shizuku](https://shizuku.rikka.app/) for other
  apps, CallVault can now borrow its privilege instead: no pairing, no debugging switch, no second
  thing to keep alive. The choice sits at the top of Settings and can be changed at any time.

  It is a genuine trade rather than a free upgrade, so the app is blunt about the cost. Recording
  app calls, resilient recording, recording away from Wi-Fi, speaker names and one-tap update
  installs all need a capture path that only CallVault's own helper can open — under Shizuku they
  are switched off and greyed out with the reason, instead of silently doing nothing. Shizuku also
  has to be started again after every reboot, where CallVault's own helper comes back on its own.

  Switching either way is reversible: the settings a mode cannot honour are turned off when you
  enter it and restored when you leave, so nothing is quietly lost. The switch waits for the new
  helper to actually be serving — and for app-call capture to be armed — before it reports success,
  because a mode change that returns early is a mode change that loses the next call.

- **Who said what.** Each line of a new transcript is attributed to the side that spoke it. The
  attribution is read off the two capture channels *during the call* — the phone records the two
  directions separately before mixing them down — so it costs nothing, needs no extra model, and is
  exact rather than inferred. It applies to calls recorded from this version onwards; a call already
  on the phone cannot be labelled this way, because the information only exists while it is being
  recorded.

- **Names, and one tap to correct them.** Which captured channel carries the other person is a
  detail Android never specifies and every manufacturer decides for itself, so CallVault has to work
  it out — and it will not pretend to be certain. Until it knows, lines read "Speaker A" and
  "Speaker B", and the transcript asks outright: *Which one is you?* One tap names both sides, on
  that transcript and every other one.

  Left alone, it guesses: on a call you placed, the first voice is the person who answered. It waits
  for two calls to agree before saying so, and then still offers to swap. **Your answer always
  wins** — and it can be changed later from "Swap names" on any transcript, because showing your own
  words as the other person's, on a record of a real conversation, is worse than showing no name at
  all.

- **Transcribe in another language, just this once.** The transcription language is pinned rather
  than detected, because auto-detect is worse in a way that is hard to spot — it writes Hebrew in
  Latin letters and merges a whole call into one block. But a pin is one answer for every call, and a
  phone that takes calls in two languages gets it wrong half the time. Turn on **Ask which language
  each time** under Transcription, and tapping Transcribe asks first, with your usual language
  already selected. The answer covers that recording only; your default stays where you left it. Off
  by default, so nothing changes for anyone who takes calls in one language.

- **Summaries know it too.** With the sides known, a summary can say what *you* agreed to and what
  the other person did, instead of describing the call from nowhere.

### Changed

- Copying or sharing a transcript now carries the same speaker names the screen shows, and stays
  neutral for exactly as long as the screen does.

### Fixed

- **App calls record again on phones that route them through the system.** Some phones — Samsung's
  One UI among them — tell every app that a WhatsApp, Telegram or Signal call is "a call in
  progress", exactly as they would for a phone call. CallVault believed them, decided your carrier
  had a call up, and stood aside from the app call you had just started. It now asks which calls
  actually belong to the carrier, so app calls record and a real phone call still takes precedence.

- **A phone call can no longer be mistaken for an app call and left unrecorded.** With app-call
  recording switched on, a call carried over Wi-Fi calling or VoLTE could look enough like an app
  call that neither half of CallVault picked it up. Losing a call is worse than any duplicate
  notification, so that decision now asks the same question.

## [2.0.0]

A major version, because two whole features arrive at once — transcription and summaries. Both run
entirely on the phone, and neither existed in any shipped release before this one.

The release that gives a recording somewhere to live: transcripts, a playback screen, notes, and
search across everything that was ever said — and now a summary of what was said.

### Added

- **Call summaries, written on this phone.** A finished transcript can be turned into a short
  account of the call: what it was about, what was decided, and what anyone said they would do.
  Nothing is uploaded and it works with no network at all, exactly like transcription. The summary
  appears on the recording's own screen, above the transcript.

- **Items that jump into the call.** Where the summary can tell which moment a decision or a
  follow-up belongs to, it puts the time beside it — and tapping that time seeks the player straight
  there. A summary made of jump points is a table of contents for a conversation.

- **Every summary says where it came from.** "Generated from the transcript" sits at the foot of the
  card, next to a button to write it again. A summary is a machine's reading of a machine's
  transcription; when it looks wrong, the transcript is one tap away.

- **The summariser is downloaded, not bundled.** It is a 3.5 GB model, so it is fetched over Wi-Fi
  only, resumes byte-for-byte if it is interrupted, and can be deleted or discarded part-way from
  Settings — which says how much has already arrived rather than offering to start again. Before the
  first download, a dialog states what it costs: the download, about 3.5 GB of memory while it runs,
  roughly a minute or two for a short call, and that it needs a recent phone. Measured on real
  hardware, not estimated.

- **It refuses rather than guesses.** No summary without a finished transcript that has words in it,
  none while a transcription is running — they cannot share the phone — and none at all if the model
  produced something that did not parse. Nothing partial is ever stored: half a call summarised is
  not a summary of the call.

### Added (earlier in this release)

- **Transcription, entirely on this phone.** Recordings can be turned into text by a speech model
  that runs locally — nothing is uploaded, and it works with no network at all. Choose a model in
  Settings by the trade you want: a small download, or the best transcript. Fourteen languages, including Hebrew,
  Arabic, Chinese and Vietnamese, plus **Detect automatically**, which leads the list and is the
  default.

- **Three ways to have it happen.** Transcribe a single call by tapping the button on its row;
  transcribe every call as it ends; or leave it to run at a time you pick, so the work lands
  overnight instead of while you are using the phone. The scheduled option is one line showing the
  time, and tapping it opens a picker.

- **It says what it is doing.** A pill beside the title while a run is going, showing how far
  through it is as a percentage, which recording is in hand, and how many are left. Tapping it opens
  the details and a way to stop. Before a run starts, an estimate of how long it will take, measured
  from how fast *this* phone actually turned out to be rather than a published figure.

- **A warning before the automatic modes**, once, with "don't ask again" — and a Settings switch to
  bring it back if you regret it.

- **Search what was said.** Search across every transcript you have and jump straight to the moment
  in the call. Whole words only.

- **A screen for each recording.** Tapping a call opens it: who it was with, a waveform drawn from
  the actual audio that you can tap or drag to scrub, ten-second skips both ways, and a speed
  control — the thing that makes a ninety-minute call reviewable at all.

- **A note on any recording.** A free-text field for what the call was about, kept separately from
  the transcript because it is the one thing that cannot be regenerated from the audio. Notes are
  deleted with the recording, exactly like transcripts: a note about a private call is as private as
  the call.

- **Playback controls inside the transcript.** Scrub, skip, play and pause without leaving the text,
  and the line being spoken lights up as it plays. Tapping any line plays the call from there.

- **Right-to-left transcripts render correctly.** Each line is judged on its own text, so a call that
  switches language mid-way reads correctly throughout, and the timestamps sit on the correct side.

- **Delete a transcript without deleting the recording**, for when you want the audio but not a
  searchable record of what was said in it.

### Changed

- **Deleting a recording kept in two places now asks which copies.** A card is not a file: a
  recording saved both on the device and in Drive is one row carrying two, so "delete this" was
  ambiguous and answered by guessing — it removed both. The confirmation now offers Both / Device
  only / Drive only, each with its size, and names what will survive. A recording that exists in one
  place is not asked at all.

- **The recording list gives the contact name room to be read.** The source badge moved under the
  play button and the row actions gave up their padding, after names were being cut to four
  characters.

- **Language lists are sorted A–Z** in the reader's own language.

### Fixed

- **Automatic language detection produced empty transcripts, for everyone.** Asking whisper to detect
  the language told it to detect the language *and stop*, so every automatic run returned nothing at
  all. This was the default setting.

- **Stop now actually stops.** Pressing stop left the phone at full CPU until the run finished by
  itself, because the work is one long call that cannot be interrupted from outside. It also left the
  recording marked as failed, and could leave half a transcript behind that looked complete. A stop
  is now a stop: it lands in under a second, keeps nothing partial, and is never reported as an
  error.

- **Transcription was slower than it needed to be, and said nothing while it ran.** The speech model
  ran on every core including the efficiency ones, which made the fast cores wait at every step
  rather than adding speed. And a long run showed a spinning circle and nothing else for minutes,
  which is indistinguishable from being stuck.

- **A background task could keep the phone busy indefinitely.** Preparing waveforms restarted itself
  every time the app came to the foreground, so it began again from the top of the list each time and
  never finished — measurable as sustained CPU use on a phone doing nothing. It now runs once, stands
  aside while a transcription is going, and only prepares the few most recent short recordings.

- **Opening a recording no longer starts playing it**, and leaving the screen stops the audio.
  Playing a private call out loud because a card was tapped is the wrong default, and it continued
  after the screen was closed.

- **The list no longer claims to be empty while it is still loading**, which made a library of sixty
  announce itself as empty for half a second on every launch.

- **Confirmations raised from a recording's screen appear there**, instead of waiting silently and
  ambushing you on the list afterwards.

- **A recording's own screen can be scrolled**, so the controls at the bottom of it can be reached at
  all.

- **"1 minutes"** — estimates now read correctly in every language.

### Under the hood

- **whisper.cpp moves from v1.7.4 (January 2025) to v1.9.3.** Nineteen months of upstream change,
  taken deliberately rather than for tidiness: llama.cpp is being vendored alongside it for
  summarisation, the two share one copy of ggml, and sharing requires them to be contemporaries.
  Anyone reproducing a build needs the submodules at these pins.

- **The speech model now runs on the performance cores only**, which changes how long a run takes.
  A phone's measured speed is therefore discarded when the thread policy changes, rather than being
  averaged away over several runs that would each quote a wrong estimate.

## [1.5.8] - 2026-08-18

### Fixed

- **Recording could stop for good, silently, and nothing said so.** A phone was found with the
  recorder dead: no daemon, no recordings, and a status card still showing nothing wrong. It had
  stayed that way through the app being force-quit *and* a full restart. Every minute CallVault tried
  to bring the recorder back, waited 45 seconds, gave up, and tried the identical thing again — a loop
  it could never escape, because an endpoint to connect to genuinely existed and connecting to it
  simply hung. Three changes, each of which alone would have shortened the outage:
  - After two failed attempts CallVault now stops trusting that connection: it tears it down, rebuilds
    it, and switches Wireless debugging on so there is a second route in. Doing exactly this by hand
    was what revived the affected phone.
  - The check for "is there a way in at all" was wrong in a way that could strand a phone with no
    route back. Having USB debugging switched on was treated as sufficient, but it only keeps
    Android's debug service running — it offers nothing for CallVault to connect *to*.
  - The failure is no longer invisible. Home now says **"Recording is down"** when recovery keeps
    failing, instead of leaving you to discover it. It stays quiet when the recorder is merely idle,
    which is normal and healthy.

- **A phone call could be mistaken for an app call.** Carrier calls are no longer treated as VoIP,
  which could suppress the recording of a real call.

## [1.5.7] - 2026-08-04

### Fixed

- **Retention now deletes what it says it deletes.** "Recordings older than the selected period are
  permanently deleted" was true only of recordings CallVault still had a record of. Measured on a
  phone set to keep one week: the list showed a convincing 64 recordings going back exactly seven
  days, while **131 files had outlived the period** — 8 on the device and 123 in Drive, the oldest by
  48 days. Four separate faults, each of which alone was enough to strand a recording for ever:
  - A delete that failed — Drive offline, a permission lost — still made CallVault forget the file.
    It then existed nowhere in the app: absent from the list, and past the reach of every future
    check. The entry is now kept when the file survives, so the next day's check tries again.
  - The daily check only ever looked at CallVault's own index, so a file missing from it was exempt
    from the retention period no matter how old it got. It now reads the recording folders too.
    Only files CallVault itself named are eligible, and a file whose age cannot be established is
    never deleted — your own audio kept in the same folder is not touched.
  - Changing **Run at** did not move the next check until the current 24-hour period happened to
    elapse. It now takes effect immediately.
  - Google Drive can renumber the account slot inside the folder link it gives out, which silently
    invalidates every saved link — uploads, deletions and folder listings all start failing, and
    re-picking the folder does not repair the ones already saved. They are now repaired
    automatically, so recordings do not become undeletable.
- **The USB-mode warning no longer disappears exactly when it matters.** Locking the screen mid-call
  can stop a recording when USB is set to a data mode, and CallVault warned about it — but only while
  the recorder was ready. Since that setting is one of the things that stops the recorder being
  ready, the warning vanished precisely when it applied. It is now shown either way, and when the
  setting cannot be read at all CallVault says so instead of staying silent.
  - One UI 8's **"Debugging only"** is recognised as a safe mode rather than an unknown one.
  - On phones whose `dumpsys usb` does not report the setting while a safe mode is selected — a
    OnePlus 12 among them — CallVault now reads it from a system property instead, so a correctly
    configured phone is recognised as such rather than staying permanently unknown.
  - Changing the USB mode no longer leaves the setting spinning on "Applying…" for tens of seconds.
    Choosing "Charging only" cuts USB data, and the app was trying to confirm the change over the
    very connection the change had just closed.
- **Hardening:** the background helper now invokes `sh` and `pkill` by absolute path, so its
  behaviour cannot depend on the shell's search path.

### Added

- **Bug reports now include the background helper's own log, and the system's.** Everything the
  recorder helper says goes to the Android log and nowhere else — it runs as a separate process that
  cannot write CallVault's log file — so a debug report showed only the app's half of the story.
  Issue [#18](https://github.com/madkongo/CallVault/issues/18) spent a week there: the log the
  reporter sent was clean end to end, because the failure lived in the half nobody could see.
  - Turning debug logging on now also enlarges Android's log buffer, and restores it when you turn
    logging off. The default holds barely a minute on a busy phone — less time than it takes to
    reproduce a problem and reach Settings to share it.
  - **Share debug logs** attaches a second file with those lines, filtered to CallVault and to the
    Android audio, security and process services, with phone numbers redacted. Other applications'
    lines are not included, and the file says at the top what was collected and what was left out.

## [1.5.6] - 2026-07-31

### Added

- **Choose what CallVault records, for each kind of call.** Phone calls and app calls now each offer
  three settings: record automatically, ask first, or ignore completely.
  - **Phone calls can be switched off entirely** (Settings ▸ Recording ▸ Phone calls). This is what
    "record app calls only" needed: turning the two auto-record toggles off never stopped CallVault
    offering a **Record** button on every single call, which is the nagging the mode is meant to
    avoid.
  - **App calls can ask first** (Settings ▸ General ▸ Experimental ▸ VoIP calls ▸ *Start
    automatically*). A notification names the calling app and offers **Record**; it disappears when
    recording starts or the call ends.
  - Both default to what they did before, so nothing changes until you choose otherwise.
- **Share a recording** from the list, and **select several at once** by holding one down. A
  selection can be shared or deleted together.
- **Deleting several at once asks which copies to remove** when any of them is saved both on the
  device and in Drive, and says what it will keep — "Device only (1 of 2)", "Feroza will be kept —
  only in Drive". A recording you asked to delete and did not get is not something you should have to
  discover for yourself.
- **PayPal alongside Ko-fi** for supporting development. Ko-fi's card payments are unavailable or
  awkward in some countries.
- **The debug log can be read and deleted from Settings ▸ Debug**, and shows its size. Previously it
  was invisible, and clearing it meant switching logging off and on again.

### Fixed

- **An update could install in the middle of a call and cut the recording in two.** Installing over
  the running app kills it, and the recording with it — seen on a real call, where the second half
  came back mislabelled. Updates now wait, including during app calls, which never register as
  telephony calls at all.
- **Nothing checked that your bit rate was one the phone's encoder accepts.** Handed a rate outside
  its range, an encoder can quietly emit audio that decodes to silence — a correctly-sized recording
  that plays as nothing. The rate is now brought inside the supported range, and what the encoder
  accepts is written to the log.

### Changed

- The **Record phone calls** switch groups the incoming and outgoing settings under it, and the
  setup wizard now offers it too — the wizard cannot be re-run, and someone installing CallVault for
  app calls alone wants it on day one.
- The release note is followed once per release by a short note about supporting development.

## [1.5.5] - 2026-07-30

### Fixed

- **VoIP calls on Samsung lost one side of the conversation.** On One UI only one app gets the
  microphone, and when the calling app restarts its own capture ours is silenced — it keeps
  delivering audio, but silent audio, so nothing noticed. CallVault now detects that and takes the
  microphone back. Measured on a Galaxy S24 FE: the longest silent gap fell from 8 seconds to 1.5.
- **Resilient recording sounded crackly on some phones.** It held back far too little audio while the
  system was still writing it, so partly-written sound was being read. Inaudible on some devices and
  constant on others.
- **Recording could stall and stay stalled.** A second connection path could hang with no timeout,
  freezing every other connection attempt behind it. Both paths are now bounded.
- The setup wizard offered no **24 kbps** option — the recommended setting, and the default — so it
  displayed 8 kbps instead and writing that back quietly downgraded recordings.

### Added

- **Settings opens as a panel** over the app instead of replacing it, so closing it is instant.
- **Settings is grouped**: a General section, and sub-sections throughout, each collapsed on entry so
  the screen is a short list rather than everything at once.
- **Recordings show when the call happened and how long it lasted** — "Yesterday 14:30 · 12:41" —
  replacing a timestamp too cramped to read.
- The wizard now asks about **resilient recording**, **VoIP recording** and **update checking**, which
  had shipped without ever being offered during setup.

## [1.5.4] - 2026-07-30

### Fixed

- **Recording could stop silently until the app was restarted.** A connect to the recorder that was
  interrupted mid-handshake could park forever, because the ADB library's connect has no timeout —
  and it parked holding the locks every other ADB operation needs. A keep-alive latch then meant the
  watchdog never tried again, so recording stayed off with nothing on screen to say so. The handshake
  and the relaunch are both bounded now, and the app recovers on its own.

### Added

- **Choose when recordings upload to Drive** — immediately, daily or weekly, under
  Settings ▸ Storage. The schedule already worked, but its picker only existed in the setup wizard,
  which cannot be re-run ([#20](https://github.com/madkongo/CallVault/issues/20)).
- **Retention moved into Storage** as a sub-section, next to the upload schedule.

## [1.5.3] — 2026-07-29

### Added
- **The status card now tells you whether recording actually works.** Until now it reported that the
  app was ready — that the pieces were connected — which is not the same as knowing a call came out
  the other end. It now reports what your real calls proved: when recording was last verified, and,
  when something went wrong, what went wrong. An empty recording, a recorder that stopped mid-call,
  an app call where only your side came through: each says so plainly instead of leaving you to find
  out weeks later.
- **Calls CallVault never saw are caught too.** A sweep of the phone's own call log finds answered
  calls that produced no recording, so a setup that quietly stopped working is surfaced by the next
  call rather than by the one you needed. Where the cause is something you can fix — no recording
  folder, Developer options switched off, a permission lost to an update — it names that cause
  instead of reporting an unexplained gap.
- **Brazilian Portuguese.** CallVault is now available in Português (Brasil), selectable under
  Settings ▸ Visual settings ▸ Language.

### Fixed
- **Every other language was behind, and now none of them are.** German, Spanish, French, Hungarian,
  Italian, Polish, Russian, Vietnamese and Chinese were each missing dozens of strings, which
  rendered in English inside an otherwise translated screen — including the whole USB-debugging
  section and the VoIP messages. All ten languages are now complete, and the build refuses to
  produce a release if a language falls behind again.
- **A recording deletion that never happened is no longer reported as success.** When the storage
  provider refused a delete, CallVault carried on as though the file were gone.
- **A misleading log line** claimed recordings always went through scrcpy, which sent bug-report
  troubleshooting down the wrong path. It now names the capture route actually taken.

## [1.5.2] — 2026-07-28

### Fixed
- **Google Drive kept announcing calls it had already saved, sometimes an hour or more after they
  ended.** Every failed copy started over with a brand-new file in your cloud folder rather than
  recognising the one already there, and it never stopped trying: one recording had been re-uploading
  for a day, leaving a second, half-finished copy of the call beside the good one. A copy now sees a
  recording that is already up there and does nothing at all.
- **A copy cut short can no longer be mistaken for a finished one.** Android kills long uploads that
  run in the background; the recording is now written under a temporary name and only takes its real
  name once every byte has arrived. A half-finished copy left by an older version is replaced.
- **A recording that cannot be copied now tells you.** After ten attempts CallVault stops and shows a
  notification, keeping the recording on your device, instead of retrying silently for days. An empty
  recording is never uploaded as though it were a saved call.
- **The scheduled cloud sweep now matches the cadence you picked.** Leaving the setup wizard without
  finishing it could strand a daily or weekly sweep, which then uploaded in batches alongside the
  per-call copy.

## [1.5.1] — 2026-07-27

### Fixed
- **Switching USB debugging on now switches Wireless debugging off straight away**, instead of waiting
  for the next time CallVault happened to re-check. The reverse already worked; this makes both
  directions immediate.

### Added
- **If you switch Wireless debugging on while it isn't needed, CallVault switches it back off** — and
  now tells you why, in a dismissible notification, rather than silently undoing what you just did.
  CallVault can tell its own changes from yours, so it never fights its own start-up, and it only does
  this when Wireless debugging is genuinely redundant: USB debugging is covering the connection *and*
  the helper is already running.

## [1.5.0] — 2026-07-27

The headline: **you can now switch Wireless debugging off** — by turning on USB debugging instead.

### Added
- **USB debugging is now offered in the app**, under Settings → Experimental and in the setup
  checklist, marked *Recommended*. Turning it on is what lets CallVault switch **Wireless debugging
  off** and keep it off. No cable is needed, and unlike Wireless debugging it opens **no network port**,
  so it is the better of the two to leave enabled. It is genuinely optional — recording works either way.

### Fixed
- **CallVault could be left unable to record after you turned USB debugging off.** With USB debugging
  on, CallVault switches Wireless debugging off; if you then turned USB debugging back off, *both* were
  off, Android stopped its debugging service, and CallVault's helper went with it — silently, until a
  call was missed. It cost a real call: the helper needed 18 seconds to come back and the call lasted
  15. CallVault now notices immediately and switches Wireless debugging back on.

### Note
- Running with **neither** switch enabled is not possible without root: Android's debugging service
  does not exist without one of them, and a helper started through it is stopped along with it. This is
  the same limit Shizuku hits — its maintainer describes it as "work as intended… nothing we can do".

## [1.4.9] — 2026-07-26

### Fixed
- **The start-up loop from 1.4.8 could still happen** if you had "Record without Wi-Fi" switched on and
  USB debugging off. 1.4.8 assumed that option kept Android's debugging service alive so Wireless
  debugging could be switched off safely. It does not — the option saves a setting rather than holding
  a live connection, so switching Wireless debugging off still restarted the service and stopped
  CallVault's helper, and the loop returned. CallVault now keeps Wireless debugging on unless USB
  debugging is enabled, which is the only thing measured to hold the service open.

### Note
- This means most people will see Wireless debugging stay on while CallVault is ready, with the
  notification explaining why. Switching it off again for "Record without Wi-Fi" users needs a
  different approach and is being worked on.

## [1.4.8] — 2026-07-26

The headline: **CallVault now becomes ready reliably on phones where Wireless debugging is the only
way in** — found on a Samsung, and it turned out to affect the app everywhere, just invisibly.

### Fixed
- **CallVault could sit flipping Wireless debugging on and off without ever becoming ready.** Android's
  debugging service shuts down when its last connection is removed, and CallVault's background helper
  runs inside it — so switching Wireless debugging off right after starting the helper killed the
  helper, which restarted it, which switched it off again. On a Galaxy S24 FE this looped six times
  over two minutes and app-call recording could not arm at all. CallVault now leaves Wireless debugging
  on when it is the only way in, and says so in its notification, pointing at the two settings
  ("Record without Wi-Fi", or USB debugging) that let it be switched off again.
- **An app call could produce a failure notification even though it recorded perfectly.** On Samsung a
  WhatsApp call also raises the phone's call state, so CallVault started a *second*, carrier-style
  recording for the same call. That one captured nothing, was saved under an unrelated contact's name
  taken from the call log, and its empty file raised an error — while the real recording was fine. The
  carrier path now stands down when an app call is already being recorded.
- **App calls could be labelled with the wrong app entirely.** On Samsung a WhatsApp call reports the
  system as the owner of the audio, so recordings were named after *Device maintenance* and shown with
  its battery icon. CallVault now ignores the system and uses the calling app's own audio.

### Note
- **Resilient recording is not confirmed working on One UI.** Its audio handoff is rejected on that
  device. It fails safely — the call is still recorded through the normal path — but the extra
  protection is not active there. Under investigation.
- The README now carries a **tested-devices table** and a short **roadmap**.

## [1.4.7] — 2026-07-26

The headline: **CallVault can now record app calls (VoIP)** — WhatsApp, Signal, Telegram — both
sides of the conversation. Opt in under Settings → Experimental → VoIP calls.

### Added
- **Recording app calls — VoIP (opt-in, experimental).** Until now CallVault only recorded carrier phone
  calls; a call placed inside a messaging app could not be captured at all without root. It now can be,
  and it records **both sides** — the other person as well as you — as a normal recording that appears
  in the list and plays like any other.
  - **Off by default.** Enable under **Settings → Experimental → VoIP calls**. Turning it on asks you
    to confirm first: recording app calls is more tightly regulated than recording phone calls, and in
    many places every participant has to agree beforehand.
  - Verified working with **WhatsApp, Signal and Telegram**. It is genuinely experimental — an app can
    block recording, and that cannot be known until a call is under way. If only your side was
    captured, CallVault tells you so rather than leaving you to discover it later.
  - Recordings are named `…_voip-<App>[_<contact>]` and show the **calling app's icon** in the list.
  - Carrier Wi-Fi calling (VoWiFi/VoLTE) is **not** covered by this — it works differently and is out
    of reach by this route.
- **The "What's new" note now covers the last three releases**, each labelled with its version, instead
  of introducing one feature and leaving the rest unmentioned. It scrolls, and appears once per update.

### Fixed
- **VoIP recordings could be attributed to the wrong app.** A Telegram call was labelled as WhatsApp,
  and later as Google, because the app was identified by scanning notifications. The app is now taken
  from the audio stream being recorded, which cannot belong to anyone else.
- **Contact names were missing for some apps.** Telegram publishes the contact in a different field
  from WhatsApp, so its calls came out unnamed.
- **Calls with no contact name showed no app icon** — a Signal call saved as `…_voip_Signal` was read
  as a call with someone *named* "Signal", losing the app badge.

### Note
- Contact names for app calls come from the calling app's own call notification, which is the only
  place Android exposes them. If notifications are turned off for that app, its recordings are saved
  correctly but without a name — Settings now says so.
- The VoIP feature's text is currently English-only.

## [1.4.6] — 2026-07-26

The headline: **recording is now resistant to the background helper being stopped mid-call** — opt in under Settings → Reliability.

### Added
- **Resilient recording (opt-in).** Until now, the background helper held the microphone and did the
  encoding for the whole call — so if Android stopped it part-way through, the recording stopped with
  it and you were left with a half-length file. With this on, the helper only *starts* the capture and
  then hands it to CallVault itself, which keeps it and does the encoding. The helper can then be
  killed at any point mid-call and the recording simply carries on to the end.
  - **Off by default**, and turning it off restores exactly the previous behaviour. Enable it under
    **Settings → Reliability**, or from the one-time note shown after updating.
  - It doesn't change how a recording *starts*, so it complements the "Charging only" USB fix from
    1.4.5 rather than replacing it.

### Fixed
- **A call could occasionally not record at all.** When starting a recording, CallVault read a USB
  setting over its ADB connection with no time limit. Normally that is instant, but if the connection
  had gone half-dead the read never returned, and the recording never started — leaving an empty file.
  It is now capped at 1.5 seconds and falls back to the last known value. This affected every
  recording, not just the new opt-in.
- **The "Voice performance" audio source now uses the fast direct path.** It was matched against a
  source name that doesn't exist, so it silently fell back to the slower scrcpy path.

### Note
- CallVault now ships a small native library and therefore requires a **64-bit ARM device**
  (`arm64-v8a`) — effectively every phone running Android 11 or newer.

## [1.4.5] — 2026-07-24

The headline: **recording no longer stops if you lock the screen during a call** — on phones where it did.

### Added
- **Keep recording when the screen locks.** On many phones (OnePlus, Xiaomi, Samsung…), locking the
  screen during a call restarts the USB connection, which was killing the recorder mid-call. CallVault
  can now set your phone's **Default USB Configuration to "Charging only"** from inside the app, which
  prevents it — no digging through system menus.
  - A new **Settings → Reliability** section lets you pick the USB mode (Charging only is recommended)
    and holds the off-Wi-Fi recording option too.
  - A **setup step** offers the one-tap fix during onboarding.
  - If USB is on a data mode, the **Home screen** and the **recorder notification** show a gentle
    "locking the screen may stop recording — tap to fix" prompt.
  - Note: with "Charging only", plugging into a PC defaults to charging — pick "File transfer" manually
    when you actually want to move files.

## [1.4.4] — 2026-07-24

### Fixed
- **Call audio quality restored at the same bitrate — the far party is clear again.** Recording captured
  the call in stereo (your side on one channel, the other person's on the other) and encoded it as
  stereo, which split the bitrate and starved each side — so the **other party** sounded noticeably
  worse at the default 24 kbps. Calls are mono content, so CallVault now downmixes to a single channel
  and gives the whole bitrate to the voice. Same setting, much better quality.
- **No false "recording paused" warning while recording actually works.** The post-update permission
  banner now only appears when a call genuinely couldn't be recorded (the recorder isn't running), not
  while it's warm and recording normally — and CallVault keeps quietly restoring the permission in the
  background.

## [1.4.3] — 2026-07-24

### Fixed
- **No more false "recording paused" warning after an update.** When an update dropped a permission
  but recording kept working (the recorder was still running), CallVault showed an alarming
  "paused after update" banner anyway. It now **silently restores the permission** over the
  connection that's already open — no action, no reinstall — and only shows a (reworded, honest)
  prompt when it genuinely can't, telling you it may still be working and how to keep it that way.

## [1.4.2] — 2026-07-24

### Added
- **Support development.** An optional "♥ Support" button next to the app title on Home, and a
  matching row in Settings → About, open the maintainer's Ko-fi page in your browser. Entirely
  optional — CallVault stays fully free and open source.

### Fixed
- **The "What's new: off-Wi-Fi recording" note no longer pops up after every update.** It's a
  one-time introduction now — shown once, then never again (a small "updated to …" banner still
  confirms an update landed).

## [1.4.1] — 2026-07-24

A focused fix for a problem some people hit after updating to 1.4.0.

### Fixed
- **Recording no longer breaks after updating without a reinstall.** When CallVault was updated in place
  (e.g. via Obtainium or a sideloaded APK), Android could quietly drop a permission the app needs to run
  the recorder — leaving recording dead until a full clean reinstall. CallVault now heals this itself:
  right after an update it reconnects over any still-open channel and restores the permission
  automatically. If it can't (nothing to reconnect through), the Home screen shows a clear
  **"Recording paused after update"** banner — tap it and turn Wireless debugging on once, and recording
  restores itself. **No reinstall needed.**

## [1.4.0] — 2026-07-23

The headline: **record calls even without Wi-Fi**, plus a much faster, more reliable recorder.

### Added
- **Offline recording (opt-in) — record with no Wi-Fi.** A new option (Settings → Debug → "Offline
  recording") lets CallVault capture calls even when you're not on a Wi-Fi network — for the important
  call you get on the road. It's **off by default** and shows a short security note when you turn it on,
  because it opens a local debugging port on your own device. You can also enable it straight from the
  "What's new" note after updating.
- **The recorder stays warm and comes back fast.** CallVault now keeps its privileged recorder ready and
  relaunches it within a few seconds when the system reclaims it — so a call after your phone has been
  idle is captured almost immediately instead of after a long "starting up" wait.

### Changed
- **New audio-capture engine.** Calls are recorded through a direct on-device audio path instead of the
  previous screen-mirroring helper. Capture begins from the first moment (no clipped beginnings), the
  daemon boots faster, and the app has fewer moving parts. (Falls back to the old path automatically if a
  device can't use the new one.)
- **One clear "ready to record" notification** instead of the occasional duplicates.

### Fixed
- **No more Wireless-Debugging notification flapping** on and off while idle.
- **Recovery after the system reclaims the recorder is now seconds, not up to a minute** — the old
  behaviour left "starting up" showing long after recording was actually ready.

## [1.3.1] — 2026-07-22

A reliability release for recordings saved to cloud folders (e.g. Google Drive), based on field reports.

### Fixed
- **Recordings to a Google Drive folder no longer fail or vanish.** Some storage providers (Google
  Drive, and other cloud/synced folders) reject the read-write mode the recorder needs, and report a
  file's size as 0 immediately after writing (their upload is asynchronous). This caused recordings to
  either fail to start (`Unsupported mode: rw`) or be **falsely detected as empty and deleted**.
  CallVault now records into on-device storage first and copies the finished file into such folders,
  and it trusts the actual captured size instead of the provider's delayed report.
- **Honest error messages.** A storage failure is no longer mislabeled as "ADB connection failed".

### Added
- **Cloud folders are blocked as the recording folder.** Picking Google Drive / OneDrive / Dropbox as
  the *device* recording folder is now refused with guidance to choose on-device storage — use the
  **Google Drive backup** option for cloud copies instead. Any existing cloud recording folder now
  shows a warning in Settings so it's no longer mistaken for local storage.

## [1.3.0] — 2026-07-21

The headline feature: **in-app updates**. CallVault can now tell you when a new version is out and
install it for you, without hunting for the APK on GitHub.

### Added
- **In-app updater (manual).** CallVault checks GitHub daily (and when you open the app) for a new
  release. When one is available you get a notification and an **Update** banner on the Home screen;
  tap it and CallVault downloads, verifies, and installs the update itself, then reopens on the new
  version and confirms with an "updated" banner + notification. No more manually downloading APKs.
  - The download is **resumable** — a slow or flaky connection resumes where it left off instead of
    restarting the ~80 MB file.
  - Every update is **signature-pinned**: the downloaded APK is checked against CallVault's release
    certificate (and must be a genuinely newer build) before anything is installed.
  - The install also re-grants the permission an app update otherwise drops, so recording keeps
    working seamlessly across updates.
  - A **"Check for updates"** toggle (Settings → Updates, on by default) turns the checks off if you
    prefer to update manually.

### Notes
- Installing is always an explicit choice — CallVault never installs an update on its own.
- Distribution is still sideload/F-Droid/Obtainium; the in-app updater is an added convenience for
  GitHub releases, not a replacement.

## [1.2.3] — 2026-07-19

### Fixed
- **Filenames no longer lose the caller for unsaved numbers.** With a file-name template using
  `{contact_name}`, calls from numbers not saved in your contacts produced a name with an empty
  contact segment — no way to tell who the recording was from. The placeholder now falls back to
  the **phone number itself** when there is no saved contact (and it's not voicemail). If your
  template already includes `{phone_number}`, the fallback stays empty so the number isn't
  written twice.

## [1.2.2] — 2026-07-19

A field-report fix release: voicemail calls get their proper name, and the app now tells the truth
when recording can't work instead of pretending everything is fine.

### Fixed
- **Voicemail calls are now labeled correctly.** Carrier voicemail short codes (e.g. `123` in
  France) were being "standardized" into an invalid international number (`+33123`), which broke
  contact-name resolution — and voicemail isn't a real contact anyway, so lookups always came up
  empty. Short codes now keep their raw form, and calls to the carrier voicemail number are labeled
  with a localized **"Voicemail"** name in file names and the recordings list.
- **No more false "Ready to record" while Developer options is off.** If Developer options is
  disabled (e.g. after an OS update or manual toggle), the recorder daemon cannot survive and every
  "recording" comes out empty — but the Home screen still showed green. It now shows a clear red
  **"Developer options is off"** status explaining what to re-enable.
- **Empty recordings are no longer saved as if they succeeded.** A 0-byte file (daemon died before
  capture started) used to be cataloged, copied to Drive, and shown as an unplayable entry
  ("Can't read this file"). It is now deleted and reported with an error notification instead.
- **You are warned during the call if recording silently stops.** The app now watches the recorder
  daemon while a recording is live and immediately notifies **"this call is NOT being recorded"**
  if the daemon dies mid-call, instead of discovering the loss after hanging up.
- **Post-reboot and startup notifications are now translated.** The "Ready to record calls /
  Listening for calls after restart" notification (and the boot-time "Preparing call recorder…")
  were hardcoded in English; they now follow the app language like everything else.

### Internal
- Cross-country detection is preserved for invalid/foreign numbers (the ignore-cross-country
  auto-record rules keep working for them).
- The unit-test harness (JUnit/Robolectric) now lives on `main`, with tests covering the number
  enrichment, voicemail matching, file-name fallback, and Developer-options detection.

## [1.2.1] — 2026-06-26

A localization release: every shipped language is now fully translated, fixing screens that
appeared in English even when the rest of the app was localized.

### Fixed
- **Onboarding and main screens now follow the selected language.** The disclaimer/first page, the
  permissions and setup wizard, and the home screen (recordings list + status) previously fell back to
  English for many strings — most visibly the **first page stayed English** even with a French
  device/app language. All shipped locales (fr, de, es, it, hu, pl, ru, vi, zh-rCN) are now at **100%
  coverage** (289 UI strings each).
- **Recorder & pairing notifications are now translated.** The cold-start "Call recorder starting up…
  / Ready to record calls" status and the wireless-debugging pairing notifications were hardcoded in
  English; they are now string resources and localized in every language (using each locale's official
  Android wording for "Wireless debugging").

### Changed
- **Translation coverage is now enforced at build time.** Lint treats `MissingTranslation` and
  `ExtraTranslation` as errors, so a new untranslated string can no longer ship silently.

### Internal
- Hardened two notification posts (`DebugNotificationHelper`, `RecorderReadinessNotifier`) with an
  explicit `POST_NOTIFICATIONS` check, resolving the corresponding `MissingPermission` lint errors.

## [1.2.0] — 2026-06-25

A reliability release focused on **recording the first call after a reboot**, plus a rebuilt,
user-facing debug/bug-report flow and an audio default better suited to voice.

### Added
- **Debug section (always visible).** Settings now has a simple **Debug** section: turn on diagnostic
  logging, see an **"ON" reminder** (in-app warning + a persistent notification) so you don't leave it
  running, and **Share debug logs** in one tap via the system share-sheet to send a bug report. Logs
  are phone-number **redacted**.
- **24 kbps audio bit rate**, flagged **Recommended** and now the **default** for Opus — plenty for
  intelligible voice; higher rates only inflate file size.

### Changed
- **Removed the hidden "Developer Options"** (the 7-tap unlock, test-call simulator, and the
  redaction-off "Debug mode"). Log **redaction is now always on** and cannot be turned off.
- After a reboot **or an app update** the app briefly shows **"Call recorder starting up…"**, flipping
  to **"Ready to record calls"** once recording is actually possible — so you know when a call will be
  captured. Only appears while the recorder daemon is cold (nothing shown when it's already warm).

### Fixed
- **First call after a reboot now records.** A new bounded post-boot **live call-state listener**
  detects calls in real time instead of relying on the system `PHONE_STATE` broadcast, which on a
  freshly-booted device could arrive **~9 seconds late** — after the call had already ended.
- **Faster recorder warm-up after boot** (~5 s vs ~15 s): trimmed a redundant Wireless-Debugging wait
  and skip the stale-daemon scan in the first 90 s after boot (a reboot already cleared any daemon).
- **Daemon cold-start no longer over-waits.** The launcher returns the instant the daemon's binder
  arrives instead of blocking out the full keep-alive window, recovering calls that were previously
  aborted 1–3 s too late.
- **Redaction can no longer be left disabled.** A leftover developer "Debug mode" flag could keep real
  phone numbers in shared logs; redaction is now unconditional.
- **Number-less recordings rename correctly.** The end-of-call CallLog rename now uses
  `DocumentsContract.renameDocument` (the previous call threw on single-document SAF URIs), so files
  get the contact/number in their name.

## [1.1.1] — unreleased

A stability + features release built on top of v1.1.0. It keeps v1.1.0's proven on-demand
Wireless-Debugging behaviour (the daemon "keep-alive" experiment that made Wireless Debugging
repeatedly turn itself on was **not** included) and adds the safe fixes plus new settings.

### Added
- **Retention / auto-delete.** Automatically delete recordings older than a chosen period
  (Daily / Weekly / Bi-weekly / Monthly, or Keep forever). Set **one period for both device & cloud,
  or a separate period for each**. A daily background sweep runs at a **time you choose**, anchored to
  the **device's local time zone** (re-anchored automatically when you change time zone). Defaults to
  off; enabling it asks for confirmation.

### Changed
- **Settings reorganised.** "Recording & storage" is now **two separate sections** — **Recording**
  (filename template, auto-record rules) and **Storage** (target, device folder, Drive folder).
- **Accordion settings.** Sections open **one at a time** (Recording open by default); opening one
  collapses the others.

### Fixed
- **Phantom Drive recordings** no longer appear in the Home list — the list is now backed by a
  standalone on-device catalog instead of Drive's stale index.
- **Folder pickers** now open at their own currently-selected folder (best-effort; OEM file pickers
  may still ignore the hint).
- **Onboarding no longer skips the ADB step** after a reinstall (Auto Backup disabled), plus clearer
  guidance for the OEM per-app battery mode (e.g. OxygenOS "Allow background activity").
- **Stuck microphone** fixed: recording start is aborted if the call ends during daemon cold-start.

### Performance
- Faster scrcpy socket connect (tighter polling).

### Security / internal
- Removed the exported debug-only broadcast receivers used during development.

## [1.1.0] — 2026-06-11

Complete visual redesign ("Signal" theme) plus UX polish: setup wizard, Home screen with in-app
playback and filters, smoother Wireless-Debugging pairing. See the
[v1.1.0 release](https://github.com/madkongo/CallVault/releases/tag/v1.1.0).
