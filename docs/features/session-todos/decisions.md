# Session Todos Decisions

## Prototype C for mobile chrome

- Decision: Session Todos use a floating pill + view-only bottom sheet
  (prototype C), not composer dock, top strip, or timeline card.
- Stack: Todos pill on top; Scroll to end XOR Changes below; hide the whole
  float stack while the todos sheet is open.
- Why: Compact over chat, matches existing float-stack patterns, keeps
  `todowrite` out of the timeline.
- Ticket: OPE-60 / OPE-97.
- Evidence: `docs/features/session-todos/prototype/VERDICT.md` (throwaway UI on
  `prototype/ope-60-session-todos-ui`).

## Sync lifecycle

- Decision: GET on live (force if cached), SSE `todo.updated` full replace,
  clear local cache on idle, force-GET next live.
- Why: Matches upstream app behavior and avoids stale lists after idle.
- Ticket: OPE-95.

## Naming and presentation rules

- Decision: Product language is **Session Todo List** / **Session Todos** /
  **Session Todo**; locked visibility, preview, progress, and sheet rules live
  in `CONTEXT.md`.
- Why: Grilled against upstream dock semantics and mobile float-stack constraints;
  avoids Tasks / Plan / Checklist confusion.
- Ticket: OPE-60 / OPE-96.

## Feature-local sheet status rows

- Decision: Session Todo sheet **rows** use design-system
  `OpenCodeSessionTodoItem` (`docs/design-system/components/session-todo-item.md`).
  The sheet shell remains `OpenCodeBottomSheet`; progress header stays
  feature-local in Session Chat.
- Why: Dock mark / strikethrough semantics needed a reusable tweak surface in
  the Component Gallery without expanding List Item’s pill/letter API.
- Ticket: OPE-97.

## Float-stack slots and layout ghosts

- Decision: Session Chat float stack is at most two pills:
  - **Primary** (nearest composer): Scroll to end **XOR** Review changes.
  - **Secondary** (above primary): Session Todos when live / finished hold.
  Compose **only visible** pills. Shared bottom inset once
  (`bottomOverlayInset` = composer stack + 16 end-pin gap — same as timeline
  pin / bottom-fade overhang; do not also add `scrollToEndPillBottomPadding`);
  spacing only between real siblings. Do not leave zero-height animation hosts
  in a spaced column.
- Why: Android `AnimatedVisibility` + `spacedBy` reserved ~8dp under Scroll to
  end even with no Todos pill, shifting its resting position. Primary baseline
  must stay fixed when secondary toggles off.
- Ticket: OPE-106.
