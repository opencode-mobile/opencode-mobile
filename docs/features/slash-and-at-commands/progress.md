# Slash & @ Commands — Progress

Linear: [OPE-56](https://linear.app/opencode-mobile/issue/OPE-56/slash-and-commands) /
[OPE-58](https://linear.app/opencode-mobile/issue/OPE-58/slash-actions)

## Shared planning

- [x] Plan, decisions, and parity docs for OPE-57
- [x] Design-system composer suggestion overlay contract + galleries (iOS/Android)
- [x] OPE-59 refined plan / decisions / parity (grill)
- [x] OPE-58 refined plan / decisions / parity (grill)

## iOS — OPE-57

- [x] Command Repository (list + run)
- [x] Composer send routing (domain + Session Chat wiring)
- [x] `/` suggestion overlay wired in Session Chat
- [x] Unit tests for list/match/submit routing and Session Chat send routing

## Android — OPE-57

- [x] Command Repository (list + run)
- [x] Composer send routing (domain + Session Chat wiring)
- [x] `/` suggestion overlay wired in Session Chat
- [x] Unit tests for list/match/submit routing and Session Chat send routing

## iOS — OPE-59

- [x] Agent Repository (list mentionable agents)
- [x] `@` trigger / insert / reconcile helpers + mutual exclusion with `/`
- [x] Session Chat composer attributed mention spans (`syntax-type`) + atomic delete
- [x] Prompt send includes `agent` parts; server-command send drops mentions
- [x] Unit tests for trigger/insert/send routing

## Android — OPE-59

- [x] Agent Repository (list mentionable agents)
- [x] `@` trigger / insert / reconcile helpers + mutual exclusion with `/`
- [x] Session Chat composer annotated mention spans (`syntax-type`) + atomic delete
- [x] Prompt send includes `agent` parts; server-command send drops mentions
- [x] Unit tests for trigger/insert/send routing

## iOS — OPE-58

- [x] Design system: `iconCircleText` button variant; composer `openCommands`
      action placed after Attach; galleries updated
- [x] Session domain: `SessionRevert` mapped from `SessionDTO.revert`
      (tolerates missing/null)
- [x] Commands bottom sheet wired from composer `openCommands`; dismisses
      slash/@ overlays, keeps draft text
- [x] `SessionChatCommands` region: `new`, `fork` (rename-dialog + Open/Stay),
      `undo`, `redo`, `compact` — all via `SessionRepository`, never
      `CommandRepository.runCommand`
- [x] Replace-navigate hook for new/forked session (see parity gap: pushes via
      the existing child-session destination rather than a backstack replace)
- [x] Unit tests for `openCommands`, visibility gating, and all five actions

## Android — OPE-58

- [x] Design system: `IconCircleText` button variant; composer `OpenCommands`
      action placed after Attach; galleries updated
- [x] Session domain: `SessionRevert` mapped from `SessionDto.revert`
      (tolerates missing/null)
- [x] Commands bottom sheet wired from composer `OpenCommands`; dismisses
      slash/@ overlays, keeps draft text
- [x] `SessionChatCommands` region: `New`, `Fork` (rename-dialog + Open/Stay),
      `Undo`, `Redo`, `Compact` — all via `SessionRepository`, never
      `CommandRepository.runCommand`
- [x] Replace-navigate hook for new/forked session (pop + push on the shared
      `NavDisplay` backstack)
- [x] Unit tests for `openCommands`, visibility gating, and all five actions

## Session Chat snackbar (follow-up)

- [x] Design system: `OpenCodeSnackbar` (`default` / `error`), galleries on both
      platforms
- [x] Session Chat one-shot `snackbar` state + `consumeSnackbar()`; sticky
      banner errors and `clientActionNotice` moved to auto-dismiss snackbars
- [x] Timeline full-screen load-error empty state unchanged (page state)

## Remaining

- [ ] None — OPE-58 is implemented on both platforms; see
      [ope-58-plan.md](./ope-58-plan.md) and [parity-gaps.md](./parity-gaps.md)
      for the recorded iOS navigation parity gap.
