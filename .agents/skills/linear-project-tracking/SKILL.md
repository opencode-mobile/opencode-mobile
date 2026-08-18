---
name: linear-project-tracking
description: Read and update Linear when the user asks about issues, backlog, or ticket refinement. Linear owns ticket detail.
---

# Linear Project Tracking

**Linear owns the ticket.** Description, acceptance, remaining work, status,
and refinements live on the issue. Do not copy a ticket into
`docs/features/` or create `plan.md` just to stay “in sync.”

Repo docs own only the durable product contract (`plan.md` current state,
still-binding decisions, open parity). Update those when the *product*
changed, not when a ticket is refined.

Use the available Linear app or connector. If Linear is unavailable, say so
and do not invent issue state.

## When the user asks about a ticket

1. Read the Linear issue (and parent/children if needed).
2. Answer from Linear. Optionally mention `docs/features/<slug>/` if a
   product contract exists.
3. If they refine the ticket, update **Linear**, not markdown.

## When implementing from a ticket

1. Read the Linear issue first.
2. Read `docs/features/<slug>/plan.md` only if it still constrains the
   product.
3. After the work ships, update Linear status. Touch `plan.md` only if the
   durable “now / remaining” product state changed.

## When the user asks to create or split tickets

Create or update issues in Linear. Link the feature docs path if one already
exists. Do not add ticket-plan files to the repo.

Workspace conventions (team, project, labels) live in
[reference.md](reference.md) and `docs/agents/issue-tracker.md`.
