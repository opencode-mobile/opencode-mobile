# PROTOTYPE — Remove project from home (Session List)

Throwaway webpage exploring the **remove-project flow** on home (the missing inverse
of [Add Project Directory Navigator](../add-project/plan.md) — a project can be added
but never removed).

**Scope decision:** only the **Session List** home mode is in play. Project
Session Selector is ignored for now.

The Session List renders the current **time ladder** shipped by
[Home Activity Grouping](../home-activity-grouping/plan.md) (OPE-107): a raised
**Recent** stack ("Needs a glance") → day sections → week sections. Project
filter chips sit in the dock and filter the whole ladder.

**Question:** What should the remove-project flow look like on home?

Sub-questions the variants explore:

- Where does the trigger live in the Session List dock (badge / manage mode /
  drag gesture)?
- Should the confirm dialog be destructive-primary (an unshipped
  `OpenCodeDialog` variant)?

## Run

From the repo root:

```bash
npm run prototype:remove-project
```

Then open http://localhost:5182/?variant=C

## Variants

| Key | Name | Trigger |
| --- | --- | --- |
| C | × on chips | Always-visible `×` on every project filter chip → confirm. Kept from iteration 1, but flagged as the misclick-prone option |
| D | Manage mode | **Manage** action in the dock arms chips with red `×`; nothing removable in normal mode → confirm. Deliberate, two-step |
| E | Drag to remove | Drag a project chip up onto the red **Remove** target; a tap still filters. Vertical gesture — no clash with the chips' horizontal scroll |

Iteration history: **A (dock overflow) — hard no**. **B (chip long-press) —
scrapped**. Replaced with D and E. C retained.

Common flow: trigger → confirm dialog (*"Remove “X” from home? Sessions stay on
the server."*) → **Remove** → project leaves the dock and its sessions leave the
ladder, filter falls back to **All**, snackbar confirms. Removing the last project
lands on the empty state.

Switch with the floating bar, `←` / `→`, or `?variant=`. Toggle dark / reset
projects with the buttons above the phone. State line under the phone surfaces
the current state after every action.

## Publish as artifact

The prototype is published for remote/LAN review via `publish-artifact` so it can
be opened in a browser without serving it locally:

```bash
publish-artifact docs/features/remove-project/prototype \
  --title "Remove project from home — flow prototype" \
  --type prototype \
  --desc "Interactive remove-project flow variants for opencode-mobile home"
```

The printed URL (e.g. `http://ivan-m2.local:8787/<date>/<slug>/`) is the
shareable review link. Republish after each iteration.

## Verdict

Pending — see [VERDICT.md](./VERDICT.md).

Not for production. Capture lives on the throwaway branch, not `main`.
