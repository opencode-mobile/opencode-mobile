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

## iOS

- [x] Messages API client + repository + adapter + Factory
- [x] SessionChatViewModel + unit tests
- [x] Session Chat wired to repository

## Tests / manual

- [x] JVM / Swift unit tests for SessionChatViewModel
- [x] Manual: API smoke-tested against local `opencode serve` (`prompt_async` 204 + message list)

## Remaining

- Server-backed composer options
- Connection Profile persistence
