# Standalone recorder daemon survives an app kill (rf/standalonekill)

🧪 VERIFYING: unit-tested policy, no real-phone run yet.

What happens: the app process is killed mid-call; the standalone daemon keeps recording. The restarted app used to kill it
(killStaleDaemons) because the hand-over never came (the `:drive` process kept the uid active, so uid-active never fired).

What the code does now
- The daemon watches the app's provider binder; on death while recording it re-delivers its binder (capped, never to a
  force-stopped app) and, if no app has called it by the time the call has been over for 35 s, stops the recording itself so the
  file is finalised (the app's cut-off rescue publishes it later).
- The launcher spares a daemon only when the session's staging file is growing, a recorder host exists and the call is not
  over (and the staged session is not an earlier call's). It then launches nothing and the engine records nothing more
  (no local ADB capture, no handoff).
- Decision A (owner): an old-APK daemon finishes the call it is recording. This only takes effect for updates FROM 2.5.2 ONWARD:
  an older daemon has none of this code and is killed by the new app's launch as before.

Known residual cases
- A VoIP call that plays USAGE_MEDIA while the audio mode is NORMAL (and no VOICE_COMMUNICATION playback) can be self-stopped
  35 s after the app died, if the app has not come back.
- Self-stop only watches playback, not active recordings: the daemon's own capture would block it forever.
- A daemon killed by SIGTERM (no app, call over, file still growing) is not finalised; logged as a warning.
- Call waiting: call B answered while call A is still up leaves no call-free moment, so the launcher cannot tell B from A
  and spares A's daemon; B is then not recorded separately (it lands in A's file). The call log cannot show it either.
- A spared daemon that cannot be reached makes the call's start stand the service down quietly (the daemon's recording is
  the call's recording); the foreground notice is the one for a kept session, not an error.
