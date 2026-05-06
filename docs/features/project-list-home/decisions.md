# Session List Home Decisions

## Use hardcoded endpoint temporarily

- Date: 2026-05-06
- Decision: show `http://127.0.0.1:4096/` as the active server context for
  session list screens on both platforms.
- Reason: unblock home-screen flow and reusable list-row work before connection
  profile infrastructure exists.
- Revisit when: connection mechanism and profile persistence are implemented.

## Keep component gallery as secondary developer route

- Date: 2026-05-06
- Decision: keep component gallery accessible from session home via a dedicated
  "Developer Components" action.
- Reason: preserve design-system review workflows while end users land on
  session-oriented home.
- Revisit when: developer-only entry routing strategy is formalized.

## Use fixed selected-project context temporarily

- Date: 2026-05-06
- Decision: home shows a fixed selected project label (`opencode-mobile`) above
  session rows.
- Reason: project selection and switching are intentionally split into a
  separate feature slice, while session-first home behavior is needed now.
- Revisit when: project selection/switching flow is implemented.
