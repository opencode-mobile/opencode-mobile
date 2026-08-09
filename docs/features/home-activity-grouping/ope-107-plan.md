# OPE-107 — Home Activity Grouping (high-level plan)

Ticket: [OPE-107](https://linear.app/opencode-mobile/issue/OPE-107/home-activity-grouping)  
Feature contract: [`plan.md`](plan.md) · Decisions: [`decisions.md`](decisions.md)

## Goal

Ship the prototype-validated **time ladder** on home Session List (iOS +
Android): Recent → last 7 local days → older by week, with a Settings-backed
Recent window. Ready from OPE-105 participates in Recent.

## Prerequisites (met)

| Dependency | Status |
| --- | --- |
| OPE-105 Session Ready on home | Done / merged (`#86`) |
| Session List home mode + floating dock | Shipped |
| `HomeDisplayPreferences` + Clear Data | Exists; extend only |
| Prototype verdict (layout A, no pin) | Locked in [`decisions.md`](decisions.md) |

## Architecture (shared intent)

```text
sessions + live status + Ready ids + recentWindowHours + now/calendar
        │
        ▼
  pure ladder grouping (no I/O)
        │
        ▼
  Home Screen Model projection
        │
        ├── filter chips (unchanged; apply before or as part of projection)
        └── Session List UI (sections + feature-local Recent chrome)
```

- **Pure grouping** owns membership and section labels; unit-test on both
  platforms without UI.
- **Screen Models** remain owners of home projection (`MainScreenViewModel` /
  `ProjectListHomeViewModel`); they call the grouper and pass sectioned data
  to the list.
- **No new server APIs.** No shared KMP module required — mirror the same
  rules in Kotlin and Swift.
- **Recent chrome** stays feature-local until a second consumer appears.

### Recent membership (locked)

A session is in Recent when any of:

1. status is **Running**, or
2. status is **Ready**, or
3. `updatedAt` is within configured **Recent window hours**

Sort Recent by `updatedAt` desc. No duplicate rows in day/week sections.

### Day / week ladder (locked)

- After removing Recent members, bucket remaining sessions by local calendar:
  - one section per distinct day among the last **7** local calendar days
  - week sections for everything older
- Omit empty sections. Within a section, keep `updatedAt` desc (current home
  sort).

## Phases

### Phase 0 — Lock remaining product knobs

Close implement-time opens before UI ships:

| Open | Proposed default (confirm at start) |
| --- | --- |
| Allowed Recent-hours values | Discrete set: **2 / 4 / 8 / 12 / 24** (default **4**) |
| Settings UI | Appearance row → existing choice sheet / picker pattern |
| Clear Data | Reset `recentWindowHours` to **4** with other home prefs |
| Recent cap | **Defer**; ship uncapped, revisit if density hurts |

Exit: decisions.md updated; no further product ambiguity for Settings.

### Phase 1 — Pure ladder grouping + tests

- Add a small pure helper (name TBD, e.g. `HomeSessionLadder`) that maps
  `(sessions, statuses/Ready, windowHours, now)` → ordered sections.
- Cover: Running/Ready force Recent; window hours; exclusion from day/week;
  empty-section omission; local day/week boundaries; filter-then-group
  invariance (grouping after project filter yields same membership rules).
- Android JVM + iOS unit tests first (TDD-friendly seam).

Exit: tests green on both platforms; no UI change yet.

### Phase 2 — Persist `recentWindowHours`

- Extend `HomeDisplayPreferences` (Android SharedPreferences + iOS
  UserDefaults).
- Default **4**; `clear()` restores default.
- Home Screen Models / list observe the preference so regrouping happens when
  it changes.

Exit: preference round-trips; Clear Data resets; no Settings UI yet optional
  (can land with Phase 3).

### Phase 3 — Settings Appearance control

- Add control under Appearance next to existing home display toggles.
- Wire to `recentWindowHours`; use Phase 0 value set and pattern.
- Copy: short label + helper that Recent also includes Running/Ready
  (avoid implying the window is the only rule).

Exit: user can change the window on both platforms; home regroups.

### Phase 4 — Home Session List ladder UI

- Project filtered sessions through the grouper.
- Render section headers for day/week; **raised Recent** stack as
  feature-local chrome (visually distinct from day/week headers).
- Preserve: open session, pull-to-refresh, live status / Ready pills, dock
  filters, empty/loading/error.
- iOS: keep design-system list items **out of** SwiftUI `List` (ScrollView +
  VStack), consistent with existing home guidance.

Exit: both apps show the ladder with filters applying to the whole list.

### Phase 5 — Parity, smoke, docs close-out

- Manual smoke: Running and Ready appear in Recent; aged idle leaves Recent
  after window; filters hide rows across sections; Settings + Clear Data.
- Record any intentional platform difference in `parity-gaps.md`.
- Update `progress.md`; mark Linear remaining checklist done when shipped.

## Risks

| Risk | Mitigation |
| --- | --- |
| Unbounded Recent when many sessions update | Soft cap deferred; watch density in smoke |
| Day/week boundary bugs across time zones | Pure helper + calendar tests with fixed `now` |
| Settings copy implies time-only Recent | Explicit helper text (Phase 3) |
| SessionList API assumed flat-only | Prefer home-owned section chrome over forcing a shared component API change; extend Session List only if both platforms need the same section primitive |

## Acceptance (feature done)

- [ ] Home shows Recent → ≤7 day sections → week sections; empty omitted
- [ ] Recent = Running ∪ Ready ∪ within window hours; no day/week duplicates
- [ ] Project filters apply to the full ladder
- [ ] `recentWindowHours` persisted, default 4, Clear Data resets
- [ ] Settings Appearance control on iOS and Android
- [ ] Unit tests for grouping on both platforms
- [ ] No pin / spotlight / inbox chrome
- [ ] Parity gaps recorded if any

## Out of this plan

Pinning, alternate layouts, push/background sync, server-configured window,
hardcoded-only window, multi-project switching beyond existing filters.
