# Message Repository — Progress

## Shared planning

- [x] Feature docs and CONTEXT glossary entry
- [x] Messages OpenAPI spec (`api/opencode-messages.openapi.yaml`)

## Design-system

- N/A (reuses outgoing message + plain text)

## Android

- [x] Messages API + repository + adapter + Hilt module
- [x] SessionChatViewModel + unit tests
- [x] Session Chat wired to repository
- [x] MessageLiveStore preserves SSE part order and appends text deltas
- [x] OPE-82: handle SSE `message.part.delta` for live assistant text streaming
- [x] OPE-82 finalization: authoritative REST reload and `session.idle` clear
  streaming state; REST-seeded part updates retain the safe reload fallback

## iOS

- [x] Messages API client + repository + adapter + Factory
- [x] SessionChatViewModel + unit tests
- [x] Session Chat wired to repository
- [x] MessageLiveStore preserves SSE part order and appends text deltas
- [x] OPE-82: handle SSE `message.part.delta` for live assistant text streaming
- [x] OPE-82 finalization: authoritative REST reload and `session.idle` clear
  streaming state; REST-seeded part updates retain the safe reload fallback

## Remaining

- No remaining implementation work in this repository slice.
- Server-backed model and Variant options shipped in
  [Model Selection](../model-selection/progress.md); agent mode is also sent with
  async prompts from Session Chat.
- Connection Profile persistence shipped in
  [Server Connection](../server-connection/progress.md).
