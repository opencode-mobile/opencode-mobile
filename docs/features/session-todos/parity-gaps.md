# Session Todos Parity Gaps

## Temporary — sheet progress header density

- Expected shared intent: Progress-first header above Session Todo Item rows.
- iOS: progress line uses `OpenCodeTypography.caption` with sheet `VStack`
  spacing 12 before the row list.
- Android: progress line uses `labelMedium` with bottom padding 4 before rows.
- Affected area: Session Todos sheet progress header only (rows share
  `OpenCodeSessionTodoItem`).
- Reason: Native type roles while landing OPE-97; row chrome is now shared.
- Next action: Align header spacing/type in a small follow-up if review wants
  pixel parity.
- Status: temporary.

## Intentional — pill exit snapshot host

- Expected shared intent: Never construct `OpenCodeSessionTodosPill` with
  `totalCount == 0`. Hide with plain conditionals (no layout ghosts).
- iOS: Plain `if`; last-positive snapshot kept for safety if exit animation
  is added later.
- Android: Plain `if` (OPE-106); no exit fade on the float stack so no
  mid-animation zero-total construction.
- Affected area: Session Chat float stack.
- Reason: Prefer stable primary baseline over exit fades; both platforms now
  omit absent pills from the stack.
- Status: intentional (shared host model after OPE-106).
