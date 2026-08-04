# Progress: Diagnostic Logs

## Shared Planning

- [x] High-level approach agreed (facade → buffer → Settings share).
- [x] Plan written (`plan.md`).
- [x] Decisions recorded (`decisions.md`): 5-minute window, 2,000-entry soft
  cap, `.jsonl` export, Clear in v1.
- [x] Implementation started.

## Design-System

- [x] Classification: reuse `OpenCodeListItem` + existing confirmation dialog;
  share sheet plumbing feature-local. No new component required.

## iOS

- [x] Diagnostic log facade + buffer + redaction tests.
- [x] Route SSE / permission / question / session high-value logs.
- [x] Settings → Developer Tools → Diagnostic Logs (Share + Clear).
- [x] Clear All Data also clears the diagnostic buffer.

## Android

- [x] Diagnostic log facade + buffer + redaction tests.
- [x] Route SSE / permission / question / session high-value logs.
- [x] Settings → Developer Tools → Diagnostic Logs (Share + Clear).
- [x] Clear All Data also clears the diagnostic buffer.
- [x] FileProvider for share export.

## Blockers

None.

## Remaining Work

- Manual QA: stuck session / pending permission export contains discriminating
  evidence without secrets.
- Confirm iOS / Android parity on device (areas, fields, Settings actions).
