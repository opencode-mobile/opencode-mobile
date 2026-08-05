# Prototype verdict — Remove project from home (Session List)

- **Date:** TBD
- **Question:** What should the remove-project flow look like on home?
- **Scope decision:** Session List home mode only; Project Session Selector is
  ignored for now. Session List renders the OPE-107 time ladder (Recent → days →
  weeks).
- **Run:** `npm run prototype:remove-project` → http://localhost:5182/?variant=C
- **Published artifact:** republished via `publish-artifact` after each iteration
  (see [README.md](./README.md#publish-as-artifact)); URL for the current build in
  the README / handoff.

## Verdict

**TBD — awaiting review of variants C / D / E.**

## Iteration history

| Iteration | Variants | Outcome |
| --- | --- | --- |
| 1 | A dock overflow · B chip long-press · C × on chips | **A hard no.** **B scrapped** (long-press plausible only hint-free, still cut). **C kept** as the misclick-prone baseline |
| 2 | C · D manage mode · E drag to remove | C retained; D and E added to solve the misclick problem |

## Rejected for now

| Variant | Why not |
| --- | --- |
| A — Dock overflow | Hard no |
| B — Chip long-press | Scrapped — possible without hint, but not pursued |

## Open sub-questions to settle with this prototype

- C's misclick risk: is the confirm dialog enough of a safeguard, or is D/E
  strictly better?
- Confirm dialog destructive-primary — ship a `destructive` option on
  `OpenCodeDialogOption`, or keep the high-contrast primary (as Delete session
  does today)?
- D manage mode: does an extra dock action ("Manage") earn its chrome?
- E drag: is the vertical-gesture-on-a-horizontal-chip-row interaction worth the
  discoverability cost?

## Folded into feature docs

Once a variant wins, product rules go into `docs/features/remove-project/plan.md`
and `decisions.md`, the parity gap in `docs/features/project-selection/parity-gaps.md`
("No remove project") is resolved, and the validated `removeWorktree` store action
lifts into the real `ConnectionProfileStore` on both platforms. Do not merge the
throwaway prototype branch into `main`.
