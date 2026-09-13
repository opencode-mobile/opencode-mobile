---
name: linear-project-tracking
description: "Linear: use to read issues or backlog, create or split tickets, refine scope, update state or links, or load an implementation contract."
---

# Linear project tracking

Linear owns ticket scope, acceptance, Remaining, status, refinements, and
temporary platform gaps. Keep that state on the issue. Do not copy Linear into
`docs/`.

Use the Linear tools available in this session. Read current state before
answering or mutating it. If Linear is unavailable, report that blocker. Do not
invent Linear state.

## 1. Route the request

Choose every requested route:

- Read: issue, backlog, parent, children, project, or current state.
- Implement: load the issue as the implementation contract.
- Create or split: create work or divide existing scope.
- Refine: change title, outcome, acceptance, Remaining, priority, labels,
  milestone, or relationships.
- Update: change status or links after external work.

Read [workspace conventions](reference.md) before Create or split, or when
Refine requires classification or state mapping.

**Completion criterion:** Every requested action maps to one route, every
mutation has an explicit target issue, and unrequested routes are excluded.

## 2. Read

Read the issue and any parent, children, project, milestone, or relationships
needed to answer the request. Answer from current Linear state. Use Refine when
the user asks to change that state.

**Completion criterion:** Every requested issue or backlog fact is supported by
current Linear data, and every unavailable fact is named without inference.

## 3. Load the implementation contract

Read the issue before implementation. Use its outcome, acceptance criteria, and
Remaining with current code and tests as the contract. Keep repository
documentation unchanged unless the user explicitly asked for documentation.

**Completion criterion:** Every in-scope implementation decision maps to the
issue, current code or tests, or an explicit user decision. Every unresolved
scope conflict is reported before it changes implementation.

## 4. Create or split

Read [workspace conventions](reference.md), then create or update issues in
Linear. Keep independently scheduled work in linked issues. Keep unscheduled
incomplete work in Remaining. Do not add ticket plans to the repository.

**Completion criterion:** Every requested issue matches the workspace
conventions, has the applicable durable labels and relationships, and owns each
incomplete item exactly once through Remaining or a linked child.

## 5. Refine

Update the named issue in Linear. Apply the issue shape, labels, state mapping,
priority, and relationship rules from
[workspace conventions](reference.md) only where the requested refinement needs
them. Preserve facts the user did not ask to change.

**Completion criterion:** Every requested refinement appears in current Linear
state, every Remaining item is actionable and unique, and no unrequested field
changed.

## 6. Update state or links

After external work, update status or links only when requested. Derive status
from the current issue and the state mapping, not from an assumed workflow.
Read the issue again after mutation.

**Completion criterion:** Every requested status or link is confirmed from
Linear, and any rejected or unavailable mutation is reported with the unchanged
current value.
