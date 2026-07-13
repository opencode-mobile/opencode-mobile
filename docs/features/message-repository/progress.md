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

## iOS

- [x] Messages API client + repository + adapter + Factory
- [x] SessionChatViewModel + unit tests
- [x] Session Chat wired to repository
- [x] MessageLiveStore preserves SSE part order and appends text deltas

## Tests / manual

- [x] JVM / Swift unit tests for SessionChatViewModel
- [x] JVM / Swift unit tests for SSE text deltas, tool replacement order, and
  hidden reasoning parts
- [x] Manual: API smoke-tested against local `opencode serve` (`prompt_async` 204 + message list)

## Remaining

- No remaining implementation work in this repository slice.
- Server-backed model and Variant options shipped in
  [Model Selection](../model-selection/progress.md); agent mode is also sent with
  async prompts from Session Chat.
- Connection Profile persistence shipped in
  [Server Connection](../server-connection/progress.md).
