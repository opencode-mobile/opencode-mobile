# Message Repository — Manual Test

Prerequisites:

1. `opencode serve` on `http://127.0.0.1:4096` (or match `AppConfig` / emulator host).
2. At least one session with optional existing messages.

## Android

1. Run the app (`./run-android.sh` or Android Studio).
2. Open Project List Home; confirm sessions load.
3. Tap a session row → Session Chat opens.
4. Confirm messages load from the server (or empty state with no seed message).
5. Type a prompt and tap Send; confirm optimistic user bubble, then timeline reload.
6. While the assistant replies, confirm its text grows incrementally rather than
   appearing only after completion.
7. When the session becomes idle, confirm the final text remains complete and
   no longer shows streaming state.
8. While a send is in flight, confirm the pending bubble stays visible if SSE or the changed-message watchdog poll refreshes the timeline before the server lists the new user message.
9. Pull down to refresh; confirm new assistant messages appear when work completed.
10. Navigate back and reopen the session; confirm history reloads on appear.
11. On iOS, navigate back from Session Chat during an active reply; confirm no stale rows reappear and no duplicate timeline polling starts after re-entry.

## iOS

Repeat steps 2–11 on the iOS app.

## Failure cases

- Stop the server and open Session Chat → error state with readable message.
- Send while offline → optimistic row removed and error shown.
- Trigger a provider failure on the server (e.g. unlicensed Copilot) → assistant bubble shows `info.error` message text, not endless `…`.

## Cross-device SSE (two apps, same session)

1. Open the same session on iOS and Android while both apps are foregrounded.
2. Send from iOS → Android chat should update within a few seconds without leaving the session.
3. Send from Android → iOS chat should update within a few seconds without leaving the session.
4. If step 3 fails but pull-to-refresh shows the message, iOS foreground observation was interrupted (regression in Session Chat lifecycle).
