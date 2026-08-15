# OPE-173 Radio-off leaves Live immediately

Parent: [Foreground Event Stream](plan.md)
Linear: [OPE-173](https://linear.app/opencode-mobile/issue/OPE-173/radio-off-leaves-live-immediately)

## Goal

When the device has no network path, Session Chat and home leave **live**
immediately. SSE occupancy stays a separate signal.

## In scope

1. `DeviceNetworkPath` (`ready` / `unavailable`) with iOS `NWPathMonitor` and
   Android `ConnectivityManager` adapters.
2. Compose chrome and mutations from SSE × path. Unavailable is **offline**.
3. On unavailable, cancel the current SSE read and park until a path exists.
   Do not `stop()` / `start()` the stream. Do not re-probe.
4. iOS mutations use the composed status, not raw SSE.

## Out of scope

- Changing live / connecting / offline labels.
- Treating path as server reachability.
- Requiring validated public internet (LAN / Tailscale stay **ready**).
- Roam debounce, Foreground Presence, background path work, push, tunnel.

## Expected result

- Radio-off while SSE is hung: chrome is **offline**; composer is blocked.
- Path returns: **connecting**, then **live** on SSE 2xx.
- Wi-Fi up and server down: **connecting**, not **offline**.
