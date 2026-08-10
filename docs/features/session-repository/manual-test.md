# Session Repository — Manual Test

## Prerequisites

1. Run `opencode serve` (default `http://127.0.0.1:4096`).
2. If the server uses a password, set `OPENCODE_SERVER_PASSWORD` in the environment
   before launching the app (Android Studio run configuration or Xcode scheme).

## Android

1. Build and run on an **emulator** (the app rewrites `127.0.0.1` → `10.0.2.2` automatically) or on a physical device using your machine's LAN IP in `AppConfig.SERVER_BASE_URL`.
2. Confirm the home screen shows a loading state, then real session titles from the server.
3. Tap a session row and confirm Session Chat opens.

## iOS

1. Build and run on Simulator (localhost / Tailscale HTTP work with `NSAllowsArbitraryLoads`).
2. Confirm the home list loads real sessions.
3. Tap a row to open Session Chat.

## API scripts

```bash
npm run api:fetch    # saves build/openapi/opencode-doc.json
npm run api:generate # optional Android codegen from api/opencode-sessions.openapi.yaml
```
