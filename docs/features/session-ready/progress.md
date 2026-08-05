# Session Ready Progress

Ticket: [OPE-105](https://linear.app/opencode-mobile/issue/OPE-105/session-ready)

## Shared Planning

- Status: **implemented on branch** `feature/ope-105-session-ready`; awaiting
  manual smoke and PR.
- Product contract and decisions locked in `plan.md` / `decisions.md`.
- Docs directory: `docs/features/session-ready/`.

## Design System

- Status: **complete**.
- `SessionListStatus.ready` on Android and iOS; pill **Ready** / `success`.
- Updated `docs/design-system/components/session-list.md` and both Session List
  galleries.

## iOS

- Status: **implemented**; `SessionReadyStoreTests` green on simulator.
- `SessionReadyStore` / `DefaultSessionReadyStore`; AppContainer DI; Clear Data
  + profile-apply `clearCache`; Session Chat focus/send hooks; home Ready
  observation + composite mapping.

## Android

- Status: **implemented**; `SessionReadyStoreTest` + mapping tests green.
- `SessionReadyStore` / `DefaultSessionReadyStore`; LiveStoreModule; Clear Data
  + profile-apply `clearCache`; Session Chat bind/`onCleared`/send hooks; home
  Ready observation + composite mapping.

## Blockers

- None for code. Manual smoke remains.

## Remaining Work

- [x] Design-system: `SessionListStatus.ready` + session-list docs/galleries
- [x] Domain: `SessionReadyStore` (watched + Ready + focus; Ready survives later
      busy until focus)
- [x] Session Chat: `notifyPromptSent` on send accepted; `setFocusedSession`
- [x] Home: observe Ready ids; composite mapping Running > Ready > idle
- [x] Clear Data + Connection Profile apply: `clearCache()`
- [x] Unit tests iOS and Android (store + home mapping)
- [ ] Manual smoke: mobile-started turn → Ready; desktop-only idle → quiet;
      open clears
- [ ] Optional later: home Needs approval pill plugging into reserved priority
      slot (not OPE-105)
