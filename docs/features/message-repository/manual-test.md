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
6. Pull down to refresh; confirm new assistant messages appear when work completed.
7. Navigate back and reopen the session; confirm history reloads on appear.

## iOS

Repeat steps 2–7 on the iOS app.

## Failure cases

- Stop the server and open Session Chat → error state with readable message.
- Send while offline → optimistic row removed and error shown.
