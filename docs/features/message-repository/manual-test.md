# Message Repository — Manual Test

Prerequisites:

1. `opencode serve` on `http://127.0.0.1:4096` (or match `AppConfig` / emulator host).
2. At least one session with optional existing messages.

## Core flow

Run this flow once on Android (`./run-android.sh` or Android Studio) and once on
iOS:

1. Launch the app.
2. Open Project List Home; confirm sessions load.
3. Tap a session row → Session Chat opens.
4. Confirm messages load from the server (or empty state with no seed message).
5. Type a prompt and tap Send; confirm optimistic user bubble, then timeline reload.
6. While the assistant replies, confirm its text grows incrementally rather than
   appearing only after completion.
7. When the session becomes idle, confirm the final text remains complete and
   no longer shows streaming state.
8. While a send is in flight, confirm the pending bubble stays visible if SSE
   or the changed-message watchdog poll refreshes the timeline before the server
   lists the new user message. Confirm the composer does not remain stuck in its
   sending state after the post-send reload finishes.
9. Pull down to refresh; confirm new assistant messages appear when work completed.
10. Navigate back and reopen the session; confirm history reloads on appear.

## Startup and synchronization races

1. Start an assistant reply from a second client, then open that session on the
   device while the reply is already streaming.
2. Confirm history appears first and every live chunk received during that
   initial load remains in order; the message must not jump backward to an
   older REST snapshot.
3. Use a session with an existing multi-part assistant message, then cause that
   row to receive a part update or removal.
4. Confirm the authoritative reload keeps all unaffected sibling parts and does
   not collapse the row to only the updated part.
5. Background and foreground the app, or otherwise force the SSE connection to
   reconnect, while the chat is open.
6. Confirm one synchronization reload occurs and the visible timeline does not
   briefly clear or duplicate rows.

## Failure cases

- Stop the server and open Session Chat → error state with readable message.
- Send while offline → optimistic row removed and error shown.
- Trigger a provider failure on the server (e.g. unlicensed Copilot) → assistant bubble shows `info.error` message text, not endless `…`.
- Interrupt server reachability as an active reply reaches idle so the
  authoritative final reload fails. Confirm the row exits streaming state
  instead of remaining permanently active. Restore reachability and confirm the
  watchdog eventually recovers the complete server text.

## Cross-device SSE (two apps, same session)

1. Open the same session on iOS and Android while both apps are foregrounded.
2. Send from iOS → Android chat should update within a few seconds without leaving the session.
3. Send from Android → iOS chat should update within a few seconds without leaving the session.
4. If either direction fails but pull-to-refresh shows the message, foreground
   observation was interrupted.

## Navigation lifecycle

On iOS, navigate back from Session Chat during an active reply. Confirm no stale
rows reappear and no duplicate timeline polling starts after re-entry. On
Android, repeat the back/re-entry flow and confirm the scoped observer stops
with the old chat destination.

## Large-history responsiveness

1. Open a session containing several long markdown messages, including fenced
   code or diffs.
2. Confirm the timeline becomes usable with plain content before all markdown
   enrichment finishes.
3. Scroll and use the composer while enrichment continues; neither interaction
   should visibly freeze.
4. Start a new assistant reply during enrichment. Confirm live text begins
   updating promptly and stale history enrichment does not delay it.
5. Confirm large live code stays readable as plain monospace text while
   streaming, then receives final formatting without a long input or scroll
   stall.
