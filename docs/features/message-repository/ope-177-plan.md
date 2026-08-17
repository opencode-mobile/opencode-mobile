# OPE-177 Silent stream catch-up

Stay on Session Chat and keep seeing new assistant work when the live
stream looks connected but stops delivering message events.

## In scope

- Watchdog REST catch-up while local text is still marked streaming if no
  message events arrive for one poll interval.
- Apply that snapshot only when REST is ahead of, or equal to and more
  finalized than, the local stream.
- Same behavior on iOS and Android.

## Out of scope

- Changing the 45s SSE byte-idle reconnect.
- Showing extra busy chrome during a healthy tool pause.
- Replaying missed SSE deltas; REST remains the catch-up authority.

## Expected result

A hung Tailscale or SSE socket that stays `connected` while the session is
busy catches up without leaving Session Chat. Live deltas still skip the
REST poll. A behind REST snapshot does not replace longer local streamed
text.
