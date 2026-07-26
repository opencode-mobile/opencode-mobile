# Foreground Event Stream — Parity Gaps

## Live Store subscriber lifecycle

- **Status:** Intentional platform difference
- **Area:** Architecture
- **Shared intent:** REST refresh runs only while a screen collects a Live Store stream; brief navigation gaps should not thrash upstream refresh.
- **Android state:** `SharingStarted.WhileSubscribed(5000)` on keyed `shareIn` caches.
- **iOS state:** `SharedLiveObservation` cancels upstream when the last `AsyncStream` subscriber terminates (no fixed stop timeout).
- **Reason:** Match platform idioms while preserving demand-driven refresh.
- **Owner or next action:** Revisit if iOS navigation causes observable refresh churn.
