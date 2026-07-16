# Session Archive and Delete — Progress

## Shared planning

- [x] Locked product contract (CONTEXT glossary + grilled decisions)
- [x] Feature docs under `docs/features/session-archive-delete/`
- [x] OpenAPI / DTO `time.archived` + `archiveSession` domain action

## Design-system

- Reused: `OpenCodeTopBarOverflowHost`, Session Chat error banner.
- Extended: optional `destructive` emphasis on `OpenCodeOverflowMenuEntry`.
- Feature-local: platform delete confirmation alert.

## Android

- [x] `archivedAtEpochMs`, `archiveSession`, home/live list filter
- [x] SessionInfo archive/delete + dismiss/error Screen Model
- [x] Session Chat overflow Archive / Delete + confirm dialog
- [x] Unit tests for adapter, archive/delete Screen Model, home filter

## iOS

- [x] `archivedAtEpochMs`, `archiveSession`, home/live list filter
- [x] SessionInfo archive/delete + dismiss/error Screen Model
- [x] Session Chat overflow Archive / Delete + confirm dialog
- [x] Unit tests for archive/delete Screen Model and home filter

## Remaining

- None for this slice.
