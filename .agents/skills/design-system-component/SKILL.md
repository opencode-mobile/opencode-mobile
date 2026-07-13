---
name: design-system-component
description: Design-system development for opencode-mobile. Use when changing semantic or component tokens; defining typography roles or platform mappings; adding or changing reusable native components, variants, states, previews, or galleries; or when feature work needs reusable UI.
---

# Design System Development

Keep one conceptual Design System across the native apps while using native
SwiftUI and Material 3 implementations.

## Route The Work

Classify the request into every applicable branch, then load only that branch's
reference:

| Branch | Use for | Required reference |
| --- | --- | --- |
| Token | Semantic or component tokens, theme resolution, platform token mappings | Read the relevant sections of [`docs/design-system/tokens.md`](../../../docs/design-system/tokens.md). |
| Typography | Semantic type roles, fonts, or platform typography mappings | Read [`docs/design-system/typography.md`](../../../docs/design-system/typography.md). |
| Component | Reusable component intent, API, variants, states, implementation, gallery, or previews | Read [`docs/design-system/components/README.md`](../../../docs/design-system/components/README.md) and the component's existing document, if any. |

For a Component branch, also consult the token or typography reference when the
component selects, adds, or changes those roles. Read
[`docs/design-system/divergences.md`](../../../docs/design-system/divergences.md)
only when the work introduces, changes, or discovers different native behavior.

Routing is complete when every requested change has a branch, affected
platforms and canonical documents are identified, and every loaded reference
is required by an active branch.

## Steps

1. **Define the shared contract before implementation.**
   - Token: specify its semantic purpose, level, light/dark behavior, and both
     platform mappings.
   - Typography: specify the role's meaning and both native mappings.
   - Component: specify intent, public API, variants, applicable states,
     content, behavior, and accessibility using the Component Documentation
     contract.

   This step is complete when every changed public decision has one shared
   meaning and an iOS and Android mapping, or an explicit `not applicable`
   reason.

2. **Update the canonical design-system documents before dependent code.**
   Update `tokens.md`, `typography.md`, or
   `components/<component-name>.md` for the active branches. For Component work,
   use the template and authoritative Definition of Done in the Component
   Documentation reference.

   This step is complete when every changed semantic decision has one
   authoritative document and component documents point to token, typography,
   gallery, and state contracts instead of redefining them.

3. **Implement the requested native changes.**
   Keep iOS and Android behavior aligned to the shared contract. For Component
   work, implement against the Component Contract in the Component
   Documentation reference.

   This step is complete when every affected platform implements the contract,
   or a concrete blocker is recorded in the completion ledger.

4. **Add review surfaces for Component work.**
   Apply the gallery and preview contract from the Component Documentation
   reference. Do not create gallery or preview work for Token or Typography
   branches unless the requested change needs a visual review surface.

   This step is complete when every applicable native gallery and preview item
   satisfies that contract, and each omitted surface has a reason.

5. **Classify every platform difference.**
   Record intentional design-system differences in `divergences.md`. Record a
   temporary mismatch caused by feature implementation in that feature's
   `parity-gaps.md`; do not place unfinished work in `divergences.md`.

   This step is complete when every observed iOS/Android mismatch is resolved,
   intentional with a divergence entry, temporary with a parity-gap entry, or
   blocked with evidence.

6. **Close the completion ledger.**
   Account for canonical docs, iOS, Android, variants and applicable states,
   gallery and previews, divergences or parity gaps, and automated checks. Mark
   each item `complete`, `not applicable` with a reason, or `blocked` with
   evidence. Run the build, lint, test, or preview checks affected by the
   change; do not claim completion while any applicable ledger item is blocked.

The work is complete only when every ledger item is accounted for and every
applicable item is complete.
