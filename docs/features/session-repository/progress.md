# Session Repository — Progress

## Shared planning

- [x] Feature docs and CONTEXT glossary entry
- [x] Sessions OpenAPI spec (`api/opencode-sessions.openapi.yaml`)
- [x] Fetch/codegen scripts

## Design-system

- N/A (reuses list item)

## Android

- [x] Hilt + Ktor + session repository
- [x] Home screen wired to `listSessions()`
- [x] ViewModel unit tests

## iOS

- [x] Factory + URLSession session client
- [x] Home ViewModel wired to `listSessions()`
- [x] ViewModel unit tests

## Tests / manual

- [x] JVM / Swift unit tests for home ViewModel
- [ ] Manual: `opencode serve` on `:4096`, open home, confirm real sessions

## Remaining

- Connection Profile persistence shipped in
  [Server Connection](../server-connection/progress.md).
- Message and prompt work shipped in
  [Message Repository](../message-repository/progress.md).
- Full `/doc` spec when server publishes session paths
