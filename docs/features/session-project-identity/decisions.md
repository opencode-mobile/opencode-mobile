# Session Project Identity Decisions

## Lock D2 chrome

- Date: 2026-08-10
- Decision: Session Chat top bar uses **D2 — letter meta**:
  - **Line 1:** session title (unchanged primary identity).
  - **Line 2:** project letter badge + **smart project label** + `·` +
    connection status (dot + lowercase label).
- Reason: Prototypes showed users need project context on chat without
  demoting the session title or dropping live/offline status. D2 keeps title
  primary, reuses home’s letter mark, and packs project + status on one caption
  line. Rejected alternatives from the same series:
  - **Baseline / E:** no always-visible project signal.
  - **A / full path subtitle:** path noise when names are unique.
  - **B context strip:** extra chrome band.
  - **C project-first title:** demotes session title.
  - **D6 tight inline:** same label rules but no letter; D2 wins for parity with
    home project marks.
- Evidence: throwaway UI prototypes
  - `docs/prototypes/session-project-info/` (initial variants)
  - `docs/prototypes/session-project-info-d2/` (D refinements)
  - `docs/prototypes/session-project-info-d3/` (D2/D6 + smart label) — locked set
- Revisit when: top-bar density fails on long titles + long disambiguated labels,
  or product wants a tappable project affordance.

## Smart project label (path only as disambiguator)

- Date: 2026-08-10
- Decision: Project chrome is a **single string**, never “name + full path” as
  two fields.
  - If the focus worktree’s basename is **unique** among added projects for the
    active server URL → show basename only (`opencode`).
  - If it **collides** → prefix the shortest parent chain that distinguishes it
    among same-named peers, rendered as `..{Parent}/[{…}/]{name}` so the leaf
    name appears once (e.g. `..Projects/opencode` vs `..Documents/opencode`).
  - If the immediate parent is also shared (e.g. both `…/lib/opencode`), walk up
    until unique (`..acme/lib/opencode` vs `..beta/lib/opencode`).
  - Peers are **added projects** on the active Connection Profile only, not the
    full server project catalog.
- Reason: Full paths are rarely needed and duplicate the basename; one parent is
  usually enough to tell `Projects/opencode` from `Documents/opencode`.
- Revisit when: added-project sets regularly need more than two path segments, or
  product wants `~` home compaction inside the smart label.

## Top bar must compose project + status

- Date: 2026-08-10
- Decision: Implement D2 by extending Session Chat’s use of `OpenCodeTopBar` so
  the second line can show **project identity and connection status together**.
  Do not drop statusDot-only behavior app-wide without a design-system update;
  Session Chat must not regress live/connecting/offline.
- Reason: Current top-bar rule ignores plain `subtitle` while
  `subtitleAccessory.statusDot` is set, which blocks D2 as specified.
- Revisit when: design-system lands a first-class `subtitleLeading` / meta-row
  API used by multiple screens.
