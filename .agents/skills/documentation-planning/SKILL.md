---
name: documentation-planning
description: Route durable opencode-mobile documentation when creating or maintaining feature plans, task plans, progress, decisions, parity gaps, or companion HTML artifacts.
---

# Documentation Planning

Treat documentation work as **artifact routing**: identify the durable fact, put
it in its canonical artifact, and keep every affected artifact consistent.

## Steps

### 1. Route the request

Identify the feature slug and classify every applicable branch:

| Branch | Canonical artifact |
| --- | --- |
| Feature scope or implementation strategy | `docs/features/<feature-slug>/plan.md` |
| Focused ticket or task plan | `docs/features/<feature-slug>/<ticket-or-task>-plan.md` |
| Implementation status or remaining work | `docs/features/<feature-slug>/progress.md` |
| Durable feature-level choice | `docs/features/<feature-slug>/decisions.md` |
| Temporary or intentional platform difference | `docs/features/<feature-slug>/parity-gaps.md` |
| Visual companion to a plan | Adjacent `.html` with the same basename |

Use an ADR only for a hard-to-reverse project decision that is surprising
without context and resulted from a real trade-off.

This step is complete when the feature slug, every applicable branch, and every
artifact that may need inspection are identified.

### 2. Load the canonical contract

For any creation or maintenance work under `docs/features/`, read
[`docs/features/README.md`](../../../docs/features/README.md), then inspect the
feature directory and every routed artifact before editing.

Handle missing paths explicitly:

- For a new feature, create its directory and the feature-wide files required by
  the README before populating them.
- For an existing feature, create a missing routed artifact only when the
  request or another affected artifact requires it. Do not create unrelated
  empty files merely to complete the standard set.
- Create a missing HTML companion when the HTML branch applies; otherwise its
  absence is expected.
- If the feature slug or canonical owner remains ambiguous after inspecting the
  repository, stop and report the competing candidates instead of guessing.

This step is complete when every routed artifact is either loaded, confirmed
absent with an applicable rule above, or recorded as blocked.

### 3. Update the routed artifacts

- Keep feature-wide intent in `plan.md`; use a task plan for focused execution
  detail instead of overloading it.
- Update `progress.md` whenever implementation status or remaining work changes.
- Record a durable choice in `decisions.md` and a cross-platform mismatch in
  `parity-gaps.md` as soon as it affects the documented feature state.
- When one change affects several artifacts, update them in the same pass so
  scope, status, decisions, and parity do not contradict one another.

This step is complete when every applicable branch is updated, explicitly
unchanged with a reason, or blocked with the missing dependency named.

### 4. Handle HTML only when routed

Create or update an HTML companion when the user requests one or when a new or
substantially revised plan contains multiple phases, mappings, or risks that are
materially easier to review visually. Do not create one when the user asks for
text only. When this branch applies, read [`HTML-PLANS.md`](HTML-PLANS.md) before
editing the companion.

Markdown remains the source of truth. HTML is a committed visual review
artifact, not a runtime asset.

This step is complete when the companion is either synchronized with its
Markdown source, intentionally omitted by request, not applicable with a
reason, or blocked with the cause recorded.

### 5. Apply the durability boundary

Keep durable scope, strategy, status, decisions, parity, handoff context, and
reusable acceptance expectations in tracked documentation. Keep scratch notes,
debug dumps, command output, screenshots, and other transient evidence out.
When transient evidence reveals a durable fact, record the fact in its routed
artifact rather than copying the raw evidence.

This step is complete when every addition is durable in its current form and no
transient evidence has entered the canonical artifacts.

## Completion Gate

Do not finish until every routed artifact and required companion is accounted
for as **updated**, **unchanged with a reason**, **not applicable with a reason**,
or **blocked with a named dependency**. Confirm that:

- all affected Markdown artifacts agree on scope, status, decisions, and parity;
- every applicable HTML companion agrees with its Markdown source;
- new or changed relative links resolve;
- no transient evidence or unrelated empty artifact was added; and
- the final diff contains only the intended durable documentation changes.

Report the artifact ledger and any blockers in the handoff.

## Skill Coordination

- Use `synced-native-feature` when the work plans or records a feature slice
  across iOS and Android.
- Use `design-system-component` when the documentation changes reusable UI
  components, tokens, variants, previews, or galleries.
- Use `linear-project-tracking` only when the same work must be reflected in
  Linear; repository documentation does not require Linear.
