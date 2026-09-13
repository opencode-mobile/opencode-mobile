---
name: design-system-component
description: Design-system development for opencode-mobile. Use when changing semantic or component tokens; defining typography roles or platform mappings; adding or changing reusable native components, variants, states, previews, or galleries; or when feature work needs reusable UI.
---

# Design System Development

Keep one conceptual Design System across the native apps while using native
SwiftUI and Material 3 implementations.

## Route The Work

Classify the request into every applicable branch:

- Token: semantic or component tokens, theme resolution, platform mappings.
- Typography: semantic type roles, fonts, or platform mappings.
- Component: reusable intent, public interface, variants, states,
  implementation, gallery, or previews.

Existing files under `docs/design-system/` may explain prior intent. Treat them
as background references, not implementation ledgers or prerequisites.

Routing is complete when every requested change has a branch, affected
platforms, and implementation locations.

Do not create or update repository documentation unless the user explicitly
requests documentation.

## Steps

1. **Define the shared interface before implementation.**
   - Token: specify its semantic purpose, level, light/dark behavior, and both
     platform mappings.
   - Typography: specify the role's meaning and both native mappings.
   - Component: specify intent, public interface, variants, applicable states,
     content, behavior, and accessibility.

   The native public interfaces and tests are authoritative. This step is
   complete when every changed public decision has one shared meaning and an
   iOS and Android mapping, or an explicit `not applicable` reason.

2. **Implement the requested native changes.**
   Keep iOS and Android behavior aligned to the shared contract. For Component
   work, keep variant and state naming conceptually aligned.

   This step is complete when every affected platform implements the contract,
   or a concrete blocker is reported.

3. **Add review surfaces for Component work.**
   Add or update native gallery and preview entries when visual review is useful.
   Do not create gallery or preview work for Token or Typography branches unless
   the requested change needs a visual review surface.

   This step is complete when every applicable native gallery and preview item
   opens successfully and each omitted surface has a reason.

4. **Classify every platform difference.**
   Align differences unless native platform behavior makes them intentional.
   Express intentional behavior in the native implementation and tests. Put
   temporary mismatches in Linear when ticket updates are in scope; otherwise
   report them in the handoff.

   This step is complete when every observed iOS/Android mismatch is resolved,
   intentional and implemented, temporary with a next action, or blocked with
   evidence.

5. **Run affected checks.**
   Account for iOS, Android, variants and applicable states, gallery and
   previews, and automated checks. Run the build, lint, test, or preview checks
   affected by the change.

The work is complete when every applicable implementation and check is complete
or a blocker is reported.
