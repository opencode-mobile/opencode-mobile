# Home Activity Grouping Decisions

Ticket: [OPE-107](https://linear.app/opencode-mobile/issue/OPE-107/home-activity-grouping)

## Time ladder layout (no pin)

- **Decision:** Home Session List groups as **Recent → last 7 days by day →
  older by week**. No pin affordance in the first cut.
- **Why:** Prototype A won for scanability; pin variants added hierarchy cost
  before the ladder is validated in product.
- **Evidence:** [`prototype/VERDICT.md`](prototype/VERDICT.md)
- **Rejected:** Spotlight rail, attention inbox, pinned shelf / pins-in-Recent /
  pin chip rail / Live·Fresh·Pin split.

## Recent membership

- **Decision:** A session is in **Recent** when any of:
  1. status is **Running**, or
  2. status is **Ready**, or
  3. `updatedAt` is within the configured **Recent window** (see below).
  Recent rows are excluded from day/week sections (no duplicates). Sort Recent
  by `updatedAt` descending.
- **Why:** Recent means “needs a glance,” not only “touched in the last N
  hours.” Running/Ready would otherwise bury under chronological sections.
- **Rejected:** Time-only Recent (hides idle overnight Ready); status-only
  Recent (hides fresh idle work); calendar-midnight-only window (misses
  overnight activity that isn’t Ready).

## Recent window is a Settings preference

- **Decision:** Persist an integer **Recent window hours** on device (both
  platforms), default **4**. Expose it in Settings under Appearance next to
  other home display preferences. Home grouping reads this value; no server
  involvement.
- **Why:** The right window is personal (laptop vs phone usage patterns); a
  hardcoded 4h is a starting guess, not a product lock. Settings keeps
  experiment cost low without shipping pin or alternate layouts.
- **Rejected:** Hardcoded hours; remote/server config; per-project windows.
- **Open for implement:** Allowed values (e.g. 1–24 or a fixed chip set like
  2 / 4 / 8 / 12 / 24), copy, and whether Clear Data resets to default (yes —
  match other `HomeDisplayPreferences`).

## Cap and overflow (soft)

- **Decision (soft):** Prefer a small Recent stack; if needed at implement time,
  cap (e.g. 5) and let overflow remain in **Today**. Not locked until
  implementation sees real density.
- **Why:** Avoid an unbounded raised stack when many sessions update in a
  burst.
