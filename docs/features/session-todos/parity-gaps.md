# Session Todos Parity Gaps

## Temporary — sheet row density

- Expected shared intent: Progress-first header + view-only status rows with
  the same mark/label semantics on both platforms.
- iOS: sheet body uses `VStack` spacing 12 and `OpenCodeTypography.caption` for
  the progress line.
- Android: sheet body uses `spacedBy(4.dp)` and `labelMedium` for the progress
  line.
- Affected area: Session Todos sheet content chrome (not status mark meaning).
- Reason: Native typography/spacing defaults while landing OPE-97; marks and
  copy rules already match.
- Next action: Align spacing/type roles in a small follow-up, or promote shared
  sheet content tokens if reuse grows.
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
