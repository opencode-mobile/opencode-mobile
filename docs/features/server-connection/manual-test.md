# Server Connection Manual Test

## Prerequisites

- `opencode serve` running on the development machine with `OPENCODE_SERVER_PASSWORD` set.
- iOS Simulator or Android emulator/device on the same network path as the server.

## First launch (no saved profile)

1. Launch the app with no prior Connection Profile (fresh install or cleared app data).
2. Confirm **Connection onboarding** appears with **Connect to server**.
3. Tap **Connect to server** and confirm the setup bottom sheet opens with defaults:
   - URL `http://127.0.0.1:4096/`
   - Username `opencode`
   - Empty password
4. Enter the server password.
5. Tap **Test connection** and confirm **Server reachable** (or auth/unreachable errors for bad input).
6. Confirm **Save** stays disabled until test succeeds.
7. Tap **Save** and confirm the app lands on project home.

## Keyboard (Android connection setup)

1. Open the connection setup bottom sheet.
2. Focus each text field in turn and confirm the focused field and action
   buttons stay visible above the keyboard without jumpy nested scrolling.

## Android emulator URL

- On Android emulator, use `http://10.0.2.2:4096/` instead of `127.0.0.1` (no auto-rewrite).

## Edit connection

1. From project home top bar, tap **Edit connection** and confirm the sheet is pre-filled.
2. Stop the server, reload home, tap **Retry** on probe failure, then **Edit connection**.

## Auth failure

1. Open setup sheet and enter an incorrect password.
2. Tap **Test connection** and confirm **Authentication failed** (or equivalent).
