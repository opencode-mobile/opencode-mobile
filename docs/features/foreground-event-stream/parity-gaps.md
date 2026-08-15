# Foreground Event Stream — Parity Gaps

## SSE start after resume

- **Status:** Resolved — [OPE-165](https://linear.app/opencode-mobile/issue/OPE-165/slow-reconnect-after-resume-from-inactivity)
- **Area:** Behavior
- **Shared intent:** Resume from inactivity reconnects without flashing
  **offline** and catch-up messages as soon as HTTP works.
- **Android state:** `start()` is idempotent; `ProcessLifecycleOwner` stop
  has a 5s grace so short overlays keep SSE. Retry loops stay on
  Connecting/Reconnecting (no Disconnected while occupied). MessageSync
  catch-up starts on Connecting and coalesces with `server.connected`.
- **iOS state:** `start()` is idempotent (no stop-then-start); cancel
  handlers are generation-guarded so Idle cannot overwrite Connecting.
  `.background` uses the same 5s stop grace as Android; `.inactive` keeps
  the stream. Retry and MessageSync catch-up match Android.
- **Reason:** Closed in OPE-165. Stop-grace parity landed in OPE-172.
- **Owner or next action:** None.

## Live Store subscriber lifecycle

- **Status:** Intentional platform difference
- **Area:** Architecture
- **Shared intent:** REST refresh runs only while a screen collects a Live Store stream; brief navigation gaps should not thrash upstream refresh.
- **Android state:** `SharingStarted.WhileSubscribed(5000)` on keyed `shareIn` caches.
- **iOS state:** `SharedLiveObservation` cancels upstream when the last `AsyncStream` subscriber terminates (no fixed stop timeout).
- **Reason:** Match platform idioms while preserving demand-driven refresh.
- **Owner or next action:** Revisit if iOS navigation causes observable refresh churn.
