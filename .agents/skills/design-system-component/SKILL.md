---
name: design-system-component
description: "Design System: use for token or typography changes, reusable component work, or Feature Slice UI dependencies."
---

# Design system

One Design System. SwiftUI on iOS, Material 3 on Android. Same meaning, native mapping.

## 1. Route the work

Assign every requested change to a branch:

- Token: semantic or component tokens, theme resolution, platform mappings.
- Typography: semantic type roles, fonts, or platform mappings.
- Component: reusable intent, public interface, variants, states,
  implementation, gallery, or previews.

`docs/design-system/` can explain why something exists. It is not a ledger.
Read it only when stable background affects the change. Keep implementation
state in code and Linear. Do not edit repository docs unless the user asked for
docs.

**Completion criterion:** Every requested change has one branch, every affected
platform is named, and every code location is identified.

## 2. Define the shared interface

- Token: semantic purpose, level, light/dark behavior, and both platform
  mappings.
- Typography: role meaning and both native mappings.
- Component: intent, public interface, variants, applicable states, content,
  behavior, and accessibility.

Native public interfaces and tests win when they disagree with older docs.

**Completion criterion:** Every changed public decision has one shared meaning
and an iOS and Android mapping, or an explicit `not applicable` reason.

## 3. Implement the native mappings

Keep iOS and Android aligned to the shared interface. For Component work, keep
variant and state names aligned across platforms.

**Completion criterion:** Every affected platform implements every mapped
decision, or a concrete blocker names the missing decision and platform.

## 4. Add Component Collection evidence

For Component work, add or update native gallery and preview entries when
visual review applies. For Token or Typography work, add them only when the
change needs a visual check.

**Completion criterion:** Every applicable gallery and preview item opens, and
each omitted item has a specific reason.

## 5. Reconcile platform differences

Align observable differences unless native platform behavior makes them
intentional. Put intentional behavior in native implementation and tests. Put
temporary mismatches in Linear when ticket updates are in scope. Otherwise,
report them in the handoff.

**Completion criterion:** Every observed iOS/Android mismatch is resolved,
intentional and implemented, temporary with a next action, or blocked with
evidence.

## 6. Check the change

Run every affected build, lint, test, or preview check. Cover iOS, Android,
variants, applicable states, gallery entries, and previews where they apply.

**Completion criterion:** Every affected implementation and check passes, has a
justified `not applicable` result, or has a reported blocker with evidence.
