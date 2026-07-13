---
name: linear-project-tracking
description: Sync opencode-mobile feature docs with Linear when feature scope or implementation state changes, or when the user asks about Linear, issues, backlog, or project tracking.
---

# Linear Project Tracking

Keep Linear as the execution board for work whose detailed source of truth lives
under `docs/features/<slug>/`. A sync is complete only when the reconciliation
contract below holds.

Use the available Linear app or connector to read and mutate current state. Tool
names vary by runtime; select tools by capability rather than relying on a fixed
MCP server name. If Linear is unavailable, finish safe repo work, report exactly
what remains unsynced, and do not claim reconciliation.

## Reconciliation Contract

For the active feature slice, all of these statements must be true:

1. Exactly one parent Linear issue represents the slice unless the docs record
   an intentional split.
2. The issue links `docs/features/<slug>/` and its title uses the product name,
   not the slug.
3. Every actionable item that is incomplete in the canonical docs appears once
   under **Remaining** or in a linked child issue.
4. No item completed or removed in the canonical docs remains open in Linear.
5. Every unresolved parity gap that requires work appears under **Remaining**
   or in a linked follow-up issue; documented intentional differences do not.
6. The issue state matches the repo signal in [reference.md](reference.md), and
   its project, milestone, labels, and dependency links match the documented
   scope.

The feature is reconciled only after every statement has been checked against
fresh reads of both the docs and Linear.

## Sync Loop

### 1. Resolve the canonical slice

Identify the feature slug, then read every existing planning artifact under
`docs/features/<slug>/` that can affect scope or state, especially `progress.md`,
`plan.md`, and `parity-gaps.md`.

If the directory or the planning artifact needed to establish scope is missing,
use the `documentation-planning` skill to create it before changing Linear. For
a new synchronized iOS and Android feature slice, use `synced-native-feature`
for the feature plan, then resume this loop.

**Complete when:** the canonical docs path exists and every documented scope,
remaining item, completed item, parity gap, and intentional deferral relevant to
the slice has been accounted for.

### 2. Resolve the Linear issue

Read current issues in the `opencode apps` project and match by docs path first,
then by product title or recorded issue key. Do not rely on a remembered issue
inventory.

If no issue matches, create one using the conventions in
[reference.md](reference.md). If multiple issues match without a documented
split, stop mutation and report the ambiguity rather than creating another.

When implementation work is about to start, set the issue to **In Progress**
before the first implementation mutation. Merely reading, planning, or updating
the tracking artifacts does not make work active.

**Complete when:** one issue is identified, its intended relationship to the
feature slice is unambiguous, and its state reflects whether implementation has
started.

### 3. Mirror each observable transition

Reconcile after any of these transitions:

- a canonical item changes between incomplete, completed, removed, or deferred;
- scope is added, split, or moved to a follow-up;
- a parity gap is opened, resolved, or declared intentional;
- implementation changes between not started, active, blocked, complete,
  superseded, or abandoned;
- a branch, dependency, milestone, platform scope, or docs path changes.

Update the canonical docs first, then update the issue description, child or
related issues, metadata, and state. Use one parent issue per cross-platform
slice unless the docs or user explicitly require separate platform issues.

**Complete when:** the reconciliation contract passes after the transition and
there is no stale open item or undocumented issue-only scope.

### 4. Reconcile before handoff

Read the docs and Linear again rather than trusting earlier snapshots. Apply the
contract exhaustively. Post a project status update only when a milestone
materially changes; describe the concrete milestone change.

Report the issue key and state, docs path, changes made, and any blocker that
prevents full reconciliation.

**Complete when:** all six contract statements pass on fresh state, or the
handoff explicitly names each failed statement and the external action needed
to satisfy it.

## Authority Boundaries

- Canonical docs own product scope, implementation progress, parity gaps,
  decisions, and intentional platform differences.
- Linear owns scheduling, assignment, priority, status, dependencies, and a
  concise projection of remaining work.
- Never resolve a disagreement by copying undocumented Linear scope into the
  repo. Confirm the scope with the user or add it through
  `documentation-planning` first.
- Agent-only maintenance and CI changes without product impact stay out of
  Linear unless the user asks to track them.

Read [reference.md](reference.md) when creating an issue, choosing its state,
milestone, labels, description, or dependency relationships.
