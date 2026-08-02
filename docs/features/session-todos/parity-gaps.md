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
  `totalCount == 0`.
- iOS: Plain conditional show; last-positive snapshot kept for safety if exit
  animation is added later.
- Android: `AnimatedVisibility` exit keeps composing during fade-out, so a
  remembered last-positive snapshot is required when abort/idle clears count
  mid-animation.
- Affected area: Session Chat float stack.
- Reason: Platform animation host difference.
- Status: intentional (same contract, different host need).
