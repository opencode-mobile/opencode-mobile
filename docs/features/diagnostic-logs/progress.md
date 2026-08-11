# Progress: Diagnostic Logs

## Shared Planning

- [x] High-level approach agreed (facade → buffer → Settings share).
- [x] Plan written (`plan.md`).
- [x] Decisions recorded (`decisions.md`): 5-minute window, 2,000-entry soft
  cap, `.jsonl` export, Clear in v1; later: local disk mirror across restart.
- [x] Implementation started.
- [x] OPE-134 main-thread hang watchdog: `hang` Diagnostic Log area, 2.5s
  detect threshold, recover logging on both platforms.

## Design-System

- [x] Classification: reuse `OpenCodeListItem` + existing confirmation dialog;
  share sheet plumbing feature-local. No new component required.

## iOS

- [x] Diagnostic log facade + buffer + redaction tests.
- [x] Route SSE / permission / question / session high-value logs.
- [x] Settings → Developer Tools → Diagnostic Logs (Share + Clear).
- [x] Clear All Data also clears the diagnostic buffer.
- [x] Persist buffer to Application Support JSONL; reload within window on
  launch; Clear deletes the file.
- [x] `MainThreadHangWatchdog` starts at app launch; unit tests cover detect /
  recover.

## Android

- [x] Diagnostic log facade + buffer + redaction tests.
- [x] Route SSE / permission / question / session high-value logs.
- [x] Settings → Developer Tools → Diagnostic Logs (Share + Clear).
- [x] Clear All Data also clears the diagnostic buffer.
- [x] FileProvider for share export.
- [x] Persist buffer to app-files JSONL; bind shared instance from DI; Clear
  deletes the file.
- [x] `MainThreadHangWatchdog` starts at `Application.onCreate`; unit tests
  cover detect / recover.

## Blockers

None.

## Remaining Work

- Manual QA: force-quit within the 5-minute window and confirm export still
  contains pre-restart evidence without secrets.
- Manual QA (OPE-134): intentional main-thread stall → force-quit → restart →
  Share Diagnostic Logs shows `hang detected` (and `hang recovered` if the
  stall ended before quit).
- Confirm iOS / Android parity on device (areas, fields, Settings actions).
- Stage 0 hang watchdog is implemented on
  `feature/ope-134-main-thread-hang-watchdog` (worktree; see OPE-134). Manual
  QA: stall → force-quit → Share Diagnostic Logs shows `hang detected`.
