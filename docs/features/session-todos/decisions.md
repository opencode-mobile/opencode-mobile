# Session Todos Decisions

## Prototype C for mobile chrome

- Decision: Session Todos use a floating pill + view-only bottom sheet
  (prototype C), not composer dock, top strip, or timeline card.
- Stack: Todos pill on top; Scroll to end XOR Changes below; hide the whole
  float stack while the todos sheet is open.
- Why: Compact over chat, matches existing float-stack patterns, keeps
  `todowrite` out of the timeline.
- Ticket: OPE-60 / OPE-97.

## Sync lifecycle

- Decision: GET when the session is open, SSE `todo.updated` full replace while
  observing, keep the local cache on idle, force-GET when live again.
- Why: TUI keeps unfinished todos after the turn; live-only hide made the top
  pill disappear as soon as the agent stopped. Supersedes the OPE-95 idle-clear
  rule.
- Ticket: OPE-168.

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

## Top overlay for Todos and Review (OPE-167)

- Decision: Session Todos and Review Changes overlay the **top** of Session
  Chat with Syncing (Syncing, then Todos, then Review). Only Scroll to end
  stays above the composer. Review is not XOR with Scroll to end. Hide Todos
  and Review while the Session Todos sheet is open; Syncing may stay.
- Why: The bottom stack covered the last message. Same capsules, new place.
- Ticket: OPE-167. Supersedes the bottom-stack slots in the OPE-106 decision
  below for product placement; still compose only visible pills.

## Float-stack slots and layout ghosts

- Decision: Session Chat float stack is at most two pills:
  - **Primary** (nearest composer): Scroll to end **XOR** Review changes.
  - **Secondary** (above primary): Session Todos when unfinished / finished hold.
  Compose **only visible** pills. Shared bottom inset once
  (`bottomOverlayInset` = composer stack + 16 end-pin gap — same as timeline
  pin / bottom-fade overhang; do not also add `scrollToEndPillBottomPadding`);
  spacing only between real siblings. Do not leave zero-height animation hosts
  in a spaced column.
- Why: Android `AnimatedVisibility` + `spacedBy` reserved ~8dp under Scroll to
  end even with no Todos pill, shifting its resting position. Primary baseline
  must stay fixed when secondary toggles off.
- Ticket: OPE-106.
