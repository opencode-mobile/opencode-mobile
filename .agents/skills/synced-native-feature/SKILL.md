---
name: synced-native-feature
description: Feature Slice development across both native apps. Use when coordinating synchronized iOS and Android planning or implementation, or when recording cross-platform progress and parity.
---

# Feature Slice Development

A Feature Slice has one shared product contract and separate native
implementations.

## Route The Work

Identify the feature directory and requested phase, then read only the applicable
sections of [`docs/features/README.md`](../../../docs/features/README.md):

- read the artifact overview and `plan.md` section for planning;
- read the `plan.md` and `progress.md` sections for implementation or status;
- read the `parity-gaps.md` section when a platform mismatch exists;
- read the `decisions.md` section when a local decision must be recorded.

That README is the single source of truth for feature artifacts and their
schemas. Routing is complete when the feature directory, active phase, affected
platforms, and required canonical sections are identified.

## Steps

1. **Close the shared plan before implementation.**
   Create or update the feature artifacts required by the canonical schema.
   Define observable shared behavior while keeping native navigation and
   implementation choices platform-specific. Align domain terms, UI state and
   actions, copy, errors, and permission behavior where they express the same
   product concept.

   Planning is complete when every applicable `plan.md` field is populated,
   every non-applicable field has a reason, every in-scope behavior has a
   checkable expected result, and both native entry points are accounted for.

2. **Classify every reusable-UI need.**
   Classify each required visual element as an existing Component Collection
   primitive, a reusable component or variant to create or extend, or
   feature-local UI. Record the chosen existing component or the reason local UI
   is not reusable.

   When reusable UI is missing, delegate it through
   [`design-system-component`](../design-system-component/SKILL.md). Do not
   resume dependent feature implementation until every applicable item in that
   skill's completion ledger is complete. If the delegated work is blocked,
   record the blocker in feature progress and stop the dependent work.

   Classification is complete when every required visual element has exactly
   one category and every delegated dependency is complete or blocks the
   dependent feature work explicitly.

3. **Implement the shared behavior on both platforms.**
   Implement each planned behavior through the native domain adapter,
   repository or service, Screen Model, and screen layers it affects. After
   each platform change, update the canonical progress fields with durable
   status rather than raw command output.

   Implementation is complete when every planned behavior is present on iOS
   and Android, or each missing platform item is recorded as unfinished work
   and, when it creates observable divergence, as a parity gap.

4. **Classify differences and decisions as they appear.**
   For every observed iOS/Android difference, either align the implementations
   or use the canonical parity-gap schema to record it immediately. Move an
   intentional, persistent design-system difference to
   [`docs/design-system/divergences.md`](../../../docs/design-system/divergences.md).
   Record feature-local decisions using the canonical decisions guidance.

   This step is complete when every observed difference is resolved, temporary
   with an owner or next action, or intentional with its durable rationale in
   the correct document; every durable local decision is recorded once.

5. **Account for affected checks.**
   Identify checks from the platforms and layers changed, run every applicable
   build, lint, test, or preview check, and report the result in the handoff.
   Update feature progress only when a result reveals a durable implementation
   status or blocker; do not add check runs or transient evidence to canonical
   feature docs. Mark a check `not applicable` only with a reason and a blocked
   check only with evidence.

   Checking is complete when every affected platform and layer has a passing
   result, a justified `not applicable` result, or a documented blocker.

6. **Close the Feature Slice ledger.**
   Account for shared planning, reusable UI, iOS, Android, parity, local
   decisions, checks, and v1 scope before handoff. Mark each item `complete`,
   `not applicable` with a reason, or `blocked` with evidence. Record only the
   durable planning, implementation, parity, decision, or blocker state in
   feature progress. A temporary parity gap counts as unfinished platform work.

The Feature Slice is complete only when every applicable ledger item is
complete, every intentional difference has durable rationale, all affected
checks pass, and the work stays within documented v1 scope or updates the
project scope first.
