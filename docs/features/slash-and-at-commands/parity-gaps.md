# Slash & @ Commands — Parity Gaps

## OPE-57

No intentional or temporary platform differences recorded for OPE-57.

## OPE-59

### `@` catalog is agents-only on mobile

- **Shared intent:** Web `@` suggestions mix references, agents, MCP resources,
  and project files (`find.files`). Mobile OPE-59 ships **mentionable agents**
  only under the same `@` trigger and suggestion overlay.
- **iOS / Android:** Agents-only catalog; quiet empty when none match.
- **Reason:** Project file / MCP / reference mentions need APIs and product
  surfaces outside v1 attachments scope; agent mentions are feasible with
  `GET /agent` alone.
- **Intentional** until a later ticket adds file/MCP/reference `@`.
- **Next action:** Keep deferred in feature out-of-scope; do not fake file rows.

## OPE-58

### Client actions use Commands sheet, not typed `/`

- **Shared intent:** Web mixes client builtins into the same `/` autocomplete as
  server commands. Mobile discovers client actions from a composer **Commands**
  `/` button and bottom sheet; typed `/` stays server-command-only (OPE-57).
- **iOS / Android:** Same Commands-sheet allowlist and hide rules.
- **Reason:** Mobile needs a persistent discovery affordance; keeping catalogs
  separate avoids mixing skills/MCP with session lifecycle actions.
- **Intentional** for this slice (typed `/` stays server commands).
- **Next action:** Revisit if product wants client rows in the typed `/` overlay.

### iOS pushes new/forked session instead of replacing the backstack entry

- **Shared intent:** After `new` or fork **Open**, the user should land on the
  new/forked session without being able to navigate "back" into the session
  they just left behind.
- **Android:** `Navigation.kt` pops the current `SessionChat` backstack entry
  and pushes the new one on the shared `NavDisplay` backstack — a true replace.
- **iOS:** `SessionChatView` pushes child sessions via a recursive
  `.navigationDestination(item:)` on its own `NavigationStack` position; there
  is no shared backstack handle to pop-then-push from a leaf view. `new` /
  fork **Open** reuse the same child-session push used for reply threads, so
  the previous session remains one level back instead of being replaced.
- **Reason:** Avoiding an app-wide navigation-stack refactor for this ticket;
  the outcome (user sees the new/forked session) matches intent even though
  the origin session stays reachable via back navigation.
- **Intentional** until iOS session navigation is restructured with a
  path/stack the leaf view can mutate directly.
- **Next action:** Revisit if/when iOS Session Chat navigation moves to a
  shared `NavigationPath` that supports true replace.

### Curated allowlist vs full desktop/TUI builtins

- **Shared intent:** Web/TUI expose many client slashes (terminal, themes, MCP
  admin, share, …). Mobile OPE-58 ships only `new`, `fork`, `undo`, `redo`,
  `compact`.
- **iOS / Android:** Same omissions, including `share` / `unshare`.
- **Reason:** v1 session APIs and existing composer chips cover the useful
  subset; share and desktop-only surfaces are separate product work.
- **Intentional** until follow-up tickets add more actions.
- **Next action:** Do not port desktop-only builtins into the Commands sheet.
