# Parity Gaps: Diagnostic Logs

## Export affordance

- Expected shared intent: user can share the `.jsonl` export and also save it
  locally (iOS “Save to Files” / Android equivalent).
- iOS: system share sheet includes Save to Files.
- Android: system share sheet does not reliably expose a save destination, so
  Share opens an `OpenCodeBottomSheet` with **Share** (apps) and **Save to
  files** (Storage Access Framework document picker, including Downloads).
- Affected area: behavior / navigation chrome around export.
- Reason: intentional platform difference for the same product outcome.
- Status: intentional.
