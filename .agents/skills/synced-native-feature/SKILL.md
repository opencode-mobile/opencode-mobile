---
name: synced-native-feature
description: Feature Slice development across both native apps. Use when coordinating synchronized iOS and Android planning or implementation.
---

# Feature Slice Development

A Feature Slice has one shared product contract and separate native
implementations.

## Route The Work

Identify the Linear issue when the work is ticket-backed, the requested phase,
and the affected platforms. Read existing repository documentation only when it
helps explain a stable constraint. It is background material, not a ledger to
update during implementation.

Do not create or update repository documentation unless the user explicitly
requests documentation.

## Steps

1. **Establish the shared behavior.**
   Use the user's request and the Linear issue as the plan. Define observable
   shared behavior while keeping native navigation and implementation choices
   platform-specific. Align domain terms, UI state and actions, copy, errors,
   and permission behavior where they express the same product concept.

   Planning is complete when in-scope behavior has checkable expected results
   and both native entry points are accounted for.

2. **Classify every reusable-UI need.**
   Classify each required visual element as an existing Component Collection
   primitive, a reusable component or variant to create or extend, or
   feature-local UI.

   When reusable UI is missing, delegate it through
   [`design-system-component`](../design-system-component/SKILL.md). Do not
   resume dependent feature implementation until its required implementation is
   complete. If the delegated work is blocked, stop the dependent work and
   report the blocker.

   Classification is complete when every required visual element has exactly
   one category and every delegated dependency is complete or explicitly
   blocked.

3. **Implement the shared behavior on both platforms.**
   Implement each planned behavior through the native domain adapter,
   repository or service, Screen Model, and screen layers it affects.

   Implementation is complete when every planned behavior is present on iOS
   and Android, or each missing platform item is reported as unfinished work.

4. **Classify platform differences.**
   Align observable iOS/Android differences unless native platform behavior
   makes the difference intentional. Express durable behavior in code and tests.
   Put temporary gaps and follow-up work in Linear when ticket updates are in
   scope; otherwise report them in the handoff.

   This step is complete when every observed difference is resolved,
   intentional and covered by the implementation, or reported with a next
   action.

5. **Account for affected checks.**
   Identify checks from the platforms and layers changed, run every applicable
   build, lint, test, or preview check, and report the result in the handoff.

   Checking is complete when every affected platform and layer has a passing
   result, a justified `not applicable` result, or a reported blocker.

The Feature Slice is complete when both platforms implement the shared
behavior or each gap is reported, and affected checks pass.
