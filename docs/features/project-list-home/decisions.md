# Session List Home Decisions

## Use hardcoded endpoint temporarily

- Date: 2026-05-06
- Status: **Superseded** (2026-05-27) by [Server Connection](../server-connection/decisions.md).
- Decision: show `http://127.0.0.1:4096/` as the active server context for
  session list screens on both platforms.
- Reason: unblock home-screen flow and reusable list-row work before connection
  profile infrastructure exists.

## Android emulator reaches host via 10.0.2.2

- Date: 2026-05-19
- Decision: on Android emulators, rewrite `127.0.0.1` / `localhost` in the
  configured base URL to `10.0.2.2` so `GET /session` reaches `opencode serve`
  on the development machine (iOS Simulator uses host loopback directly).
- Reason: emulator loopback is not the host machine.
- See: [`session-repository/manual-test.md`](../session-repository/manual-test.md).

## Keep component gallery as secondary developer route

- Date: 2026-05-06
- Decision: keep component gallery accessible from session home via a dedicated
  "Developer Components" action.
- Reason: preserve design-system review workflows while end users land on
  session-oriented home.
- Revisit when: developer-only entry routing strategy is formalized.
- Update (2026-05-20): home uses the `widgets` catalog icon and label
  **Developer components**; see [Project Selection decisions](../project-selection/decisions.md).

## Use fixed selected-project context temporarily

- Date: 2026-05-06
- Status: **Superseded** by
  [Project Selection](../project-selection/decisions.md).
- Decision: home shows a fixed selected project label (`opencode-mobile`) above
  session rows.
- Reason: project selection and switching are intentionally split into a
  separate feature slice, while session-first home behavior is needed now.

## Avoid List disclosure chevrons on session rows (iOS)

- Date: 2026-05-09
- Decision: drive navigation to Session Chat with an explicit `Button` and
  `navigationDestination(item:)` binding instead of `NavigationLink` inside
  `List`.
- Reason: `NavigationLink` rows in `List` get a system disclosure chevron; the
  product layout should match Android (no trailing chevron) for session rows.
- Revisit when: if Apple exposes a first-class way to suppress list disclosure
  without changing navigation pattern, consider simplifying.
