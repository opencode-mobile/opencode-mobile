# Feature Documentation

Feature work should be documented as a synchronized Feature Slice across iOS and
Android.

The Android app lives under [`android/`](../../android/) and the iOS app under
[`ios/OpenCode Mobile/`](../../ios/OpenCode%20Mobile/).

Create one directory per feature:

```text
docs/features/<feature-slug>/
├── plan.md
├── progress.md
├── parity-gaps.md
└── decisions.md
```

The four feature-wide files describe shared intent and state. Add these artifacts
as needed for focused execution work:

```text
docs/features/<feature-slug>/<ticket-or-task>-plan.md
docs/features/<feature-slug>/<ticket-or-task>-plan.html
```

A task-specific plan does not replace `plan.md`. An HTML file is an optional
visual companion with the same basename as its Markdown source, not a separate
source of truth.

## `plan.md`

Use `plan.md` to define the shared product intent before implementation.

Include:

- user goal;
- in-scope behavior;
- out-of-scope behavior;
- server APIs involved;
- expected platform logs for API calls, live updates, and important mutations;
- domain objects involved;
- Screen Model UI states and user actions or events;
- native navigation entry points;
- error states;
- permission request behavior, if relevant;
- design-system components needed and whether each is existing, new or extended,
  or intentionally feature-local.

Record a reason for any field that does not apply instead of leaving its status
ambiguous.

## `progress.md`

Use `progress.md` as the working log for implementation status.

Track:

- shared planning status;
- design-system dependencies;
- iOS implementation status;
- Android implementation status;
- blockers affecting implementation;
- remaining work.

Keep transient command output, screenshots, and debug evidence out of canonical
feature progress. Record any durable status or decision revealed by that
evidence instead of copying the raw artifact.

## `parity-gaps.md`

Use `parity-gaps.md` for temporary or intentional differences between the two
apps.

Each gap should include:

- expected shared intent;
- current iOS state;
- current Android state;
- affected area: behavior, state model, copy, visual semantics, token mapping,
  accessibility behavior, error handling, or missing implementation;
- reason;
- owner or next action;
- status: temporary or intentional.

Move broad design-system differences that are meant to persist into
`docs/design-system/divergences.md`.

## `decisions.md`

Use `decisions.md` for feature-level decisions that are useful locally but do
not rise to the level of an ADR.

Create an ADR instead only when a decision is hard to reverse, surprising
without context, and the result of a real trade-off.
