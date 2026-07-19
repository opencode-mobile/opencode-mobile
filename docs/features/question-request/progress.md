# Question Request — Progress

## Shared planning

- [x] Upstream web/desktop behavior researched
  (`docs/research/opencode-question-tool.md`)
- [x] Grilling decisions locked (`decisions.md`)
- [x] Feature plan documented (`plan.md`)
- [x] Glossary term added (`CONTEXT.md` — question request)
- [x] Linear parent [OPE-64](https://linear.app/opencode-mobile/issue/OPE-64/question-request)
  with children [OPE-65](https://linear.app/opencode-mobile/issue/OPE-65/design-system-session-composer-question-state),
  [OPE-66](https://linear.app/opencode-mobile/issue/OPE-66/answer-question-requests-in-session-chat)

## Design system

- [x] [OPE-65](https://linear.app/opencode-mobile/issue/OPE-65/design-system-session-composer-question-state)
  — `OpenCodeSessionComposer` question state + Gallery
  (`docs/design-system/components/session-chat-composer.md`)

## Session Chat

- [ ] [OPE-66](https://linear.app/opencode-mobile/issue/OPE-66/answer-question-requests-in-session-chat)
  — live store, SSE, tree, reply/dismiss, composer wiring (blocked by OPE-65)

## Blockers

- OPE-66 blocked by OPE-65 until this branch merges. Timeline hide rules and
  completed interact chrome already exist under Assistant Tool Parts.

## Remaining work

- Implement OPE-66 per `plan.md` and `decisions.md` (blocked until OPE-65
  ships; design-system question state is done on this branch).
