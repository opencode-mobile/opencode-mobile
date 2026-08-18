---
name: documentation-planning
description: Route durable opencode-mobile documentation when creating or maintaining feature plans, decisions, or parity gaps.
---

# Documentation Planning

Treat documentation work as **artifact routing**: identify the durable fact, put
it in its canonical artifact, and keep every affected artifact consistent.

Prefer **less**. Docs that go stale block later work.

## Steps

### 1. Route the request

Identify the feature slug and classify every applicable branch:

| Branch | Canonical artifact |
| --- | --- |
| Current feature state (goal, now, remaining) | `docs/features/<feature-slug>/plan.md` |
| Still-binding feature choice | `docs/features/<feature-slug>/decisions.md` |
| Open iOS vs Android gap | `docs/features/<feature-slug>/parity-gaps.md` |

Do **not** create `progress.md`, ticket plans, research companions, web-specs,
or soak reports. Put remaining work in `plan.md`. History is git.

Use an ADR only for a hard-to-reverse project decision that is surprising
without context and the result of a real trade-off.

This step is complete when the feature slug, every applicable branch, and every
artifact that may need inspection are identified.

### 2. Load the canonical contract

For any creation or maintenance work under `docs/features/`, read
[`docs/features/README.md`](../../../docs/features/README.md), then inspect the
feature directory and every routed artifact before editing.

Handle missing paths explicitly:

- For a new feature, create `plan.md` only. Add `decisions.md` or
  `parity-gaps.md` when a fact belongs there.
- Do not create empty files to complete a set.
- If the feature slug remains ambiguous, stop and report candidates.

This step is complete when every routed artifact is either loaded, confirmed
absent with an applicable rule above, or recorded as blocked.

### 3. Update the routed artifacts

- Rewrite `plan.md` as current state. Do not append a ticket diary.
- Record a still-binding choice in `decisions.md`. Reverse by replacing, not
  stacking superseded essays.
- Record an open cross-platform mismatch in `parity-gaps.md`. Delete the file
  when no gaps remain.
- When one change affects several artifacts, update them in the same pass.

This step is complete when every applicable branch is updated, explicitly
unchanged with a reason, or blocked with the missing dependency named.

### 4. Do not create HTML companions

HTML plan companions are retired. Markdown is the source of truth. Throwaway
review HTML is not committed.

This step is complete when no HTML companion exists or is created for the
routed artifacts.

### 5. Apply the durability boundary

Keep current scope, remaining work, binding decisions, and open parity in
tracked documentation. Keep scratch notes, debug dumps, command output,
screenshots, and research dumps out of git.

This step is complete when every addition is durable in its current form and no
transient evidence has entered the canonical artifacts.

## Completion Gate

Do not finish until every routed artifact is accounted for as **updated**,
**unchanged with a reason**, **not applicable with a reason**, or **blocked
with a named dependency**. Confirm that:

- all affected Markdown artifacts agree;
- new or changed relative links resolve;
- no transient evidence or empty artifact was added; and
- the final diff contains only the intended durable documentation changes.

Report the artifact ledger and any blockers in the handoff.

## Skill Coordination

- Use `synced-native-feature` when the work plans or records a feature slice
  across both native apps.
- Use `design-system-component` when the documentation changes reusable UI.
- Use `linear-project-tracking` only when the same work must be reflected in
  Linear.
