---
name: synced-native-feature
description: "Feature Slice: use when planning or implementing shared product behavior across iOS and Android."
---

# Feature Slice

One shared product contract. Separate native implementations.

## 1. Route the Feature Slice

Identify the requested phase, affected platforms, and Linear issue when the
work is ticket-backed. Read repository docs only when a stable constraint bears
on the Feature Slice. Keep implementation state and temporary platform gaps in
Linear. Do not create or update repository documentation unless the user asked
for documentation.

**Completion criterion:** Every requested behavior has a phase, both native
entry points are identified or marked `not applicable`, and ticket-backed work
has one Linear issue.

## 2. Establish shared behavior

Use the user's request and Linear issue as the plan. Define observable shared
behavior. Keep native navigation and implementation choices platform-specific.
Align domain terms, Screen Model state and actions, copy, errors, and permission
behavior where they express the same product concept.

**Completion criterion:** Every in-scope behavior has checkable expected
results, aligned product terms and Screen Model behavior, and an accounted-for
iOS and Android entry point.

## 3. Classify Component Collection needs

Classify every visual element as an existing Component Collection item, a
reusable component or variant to create or extend, or feature-local UI. When
reusable UI is missing, delegate it through
[`design-system-component`](../design-system-component/SKILL.md). Resume
dependent feature work after the Design System dependency meets its completion
criterion. If it is blocked, stop the dependent work and report the blocker.

**Completion criterion:** Every required visual element has exactly one
classification, and every Design System dependency is complete or blocked with
the affected feature work named.

## 4. Implement both native mappings

Implement each planned behavior through every affected native domain adapter,
repository or service, Screen Model, and screen layer.

**Completion criterion:** Every planned behavior is present on iOS and Android,
or each missing platform item is reported with the affected layer and remaining
work.

## 5. Reconcile platform differences

Align observable iOS/Android differences unless native platform behavior makes
them intentional. Express durable behavior in code and tests. Put temporary
gaps and follow-up work in Linear when ticket updates are in scope. Otherwise,
report them in the handoff.

**Completion criterion:** Every observed difference is resolved, intentional
and implemented, temporary with a next action, or blocked with evidence.

## 6. Check the Feature Slice

Identify checks from the changed platforms and layers. Run every applicable
build, lint, test, or preview check. Report each result in the handoff.

**Completion criterion:** Every affected platform and layer has a passing
result, a justified `not applicable` result, or a reported blocker. Every
remaining Feature Slice gap is named.
