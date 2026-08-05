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
  involvement. Clear Data resets to default with other `HomeDisplayPreferences`.
- **Why:** The right window is personal (laptop vs phone usage patterns); a
  hardcoded 4h is a starting guess, not a product lock.
- **Rejected:** Hardcoded hours; remote/server config; per-project windows.

## Recent-hours values and Settings UI (Phase 0)

- **Decision:** Allowed values are the discrete set **2 / 4 / 8 / 12 / 24**
  hours (default **4**). Invalid stored values coerce to the nearest allowed
  value (ties prefer the default set member closest to 4, else the closer
  larger). Settings → Appearance shows a Theme-style row + bottom sheet of
  those options. Subtitle is `"N hours"`. Helper subtitle on the sheet or row:
  Recent also includes Running and Ready. Clear Data resets to **4**.
- **Why:** Discrete choices match existing Theme picker UX and avoid a free-form
  stepper for a preference users change rarely.
- **Rejected:** Free-form 1–24 stepper; segmented control in the list row.

## Cap and overflow (soft)

- **Decision:** v1 ships **uncapped** Recent. Cap / Today overflow remains a
  soft follow-up if real density hurts scanability.
- **Why:** Avoid inventing hierarchy before evidence from smoke with real data.

## Grouping seam

- **Decision:** Pure `HomeSessionLadder` in domain; Session List applies project
  filter then groups with `recentWindowHours` and row status/updatedAt.
- **Why:** Keeps calendar math unit-testable and avoids duplicating ladder logic
  in both home ViewModels while the flat→row map still lives in the home views.

## Recent stack chrome is plain header (variant A)

- **Decision:** Recent uses the same plain section header language as day/week
  (`RECENT` uppercase, `text-base`) — no raised card, border, or accent tint.
  Recent rows show a **status dot** at the bottom-trailing corner of the letter
  badge (Running → interactive, Ready → success, Idle → info / `text-base`), with a
  `background-base` ring. Day/week rows omit the dot.
- **Why:** Prototype A won for simplicity; dots carry status glance without
  competing chrome. Accents stay on status semantics, not section framing.
- **Rejected:** Tone shelf (B), List Group card (C), interactive/primary Recent
  outline or label color.
