# OPE-166 Home Session List hides newest sessions

Parent feature: [Liquid Glass Home List](plan.md)
Linear: [OPE-166](https://linear.app/opencode-mobile/issue/OPE-166/home-session-list-hides-newest-sessions)

## Goal

When the user is at the top of the home Session List, newest sessions stay
visible. Creating a session and returning to home shows that session at the top.

## Symptom

1. Create a session, go back to home — the new session is not at the top of the
   viewport.
2. Stay at the top, another device creates sessions, resume — those sessions are
   above the viewport. The list still looks like it is at the top.

## Cause

Keyed `LazyVStack` / `LazyColumn` keeps the previously first visible session
identity on screen when newer rows are prepended. Offset `0` is then the old
first row, not the true content top.

## In scope

- If the user is following the top, pin to the true top when newer sessions
  prepend (local create, other device, resume refresh).
- Creating a session from home forces reveal of the newest rows when the user
  returns.
- If home has been off-screen for 10 minutes (chat, settings, or background),
  the next home appear resets to the true top.
- If the user has scrolled away and returns sooner than 10 minutes, leave
  their place alone.
- Same behavior on iOS and Android Session List.

## Out of scope

- Changing time-ladder grouping or sort order.
- Scroll-to-top chrome / jump buttons.
- Persisting scroll position across process death beyond platform defaults.

## Expected result

- At top + newer sessions arrive → newest session is visible at the top.
- Create session → back to home → that session is visible at the top.
- Home hidden ≥ 10 minutes → next appear shows the newest sessions at the top.
- Scrolled down + newer sessions arrive → viewport stays on the same rows.
