# OPE-169 — Design-system Assistant Message

## User outcome

Assistant replies in Session Chat use a reusable design-system message card.
Snapshot-evidence hosts reuse the same chrome instead of an ad-hoc bubble.

## In scope

- Promote the feature-local assistant bubble into `OpenCodeAssistantMessage`.
- Document the component contract, tokens, gallery, and previews.
- Replace Session Chat and snapshot-evidence ad-hoc bubbles.
- Keep Session Chat wrappers for message-action gestures and late large-markdown
  paint deferral.

## Out of scope

- Message actions, restore, copy, or fork.
- Changing Outgoing Message, Tool Call Card, or Child Session Row.
- Moving markdown paint deferral into the design-system component.
- New server APIs.

## Design system

- New: Assistant Message.
- Existing: Outgoing Message remains outgoing-only; Rendered Markdown stays the
  inner document renderer.

## Acceptance

- Gallery entries open on iOS and Android with short, long, and empty samples.
- Session Chat assistant text still matches the previous bubble, including `…`
  while empty and markdown when prepared.
- Snapshot-evidence isolated and in-session hosts use `OpenCodeAssistantMessage`.
- Message-action gestures and ≥3k late-markdown deferral stay in Session Chat.
