# Project Selection — Parity Gaps

## directory query undocumented

- **Intent:** Scope API calls to a project worktree like the web UI.
- **iOS/Android:** Append `?directory=` on scoped routes.
- **Reason:** Live server accepts it; `/doc` omits it.
- **Status:** Temporary.

## No remove project

- **Intent:** User can curate added projects.
- **Status:** Resolved — removal ships in Session List home mode via
  [Remove Project From Home](../remove-project/plan.md) (OPE-108); Project
  Session Selector affordance still out of scope there.

## iOS home section order (fixed)

- **Intent:** Project sections stay in add order across refreshes.
- **Was:** `Set` in UserDefaults save and `TaskGroup` append order shuffled sections.
- **Status:** Fixed — order-preserving dedupe and input-order section assembly.
