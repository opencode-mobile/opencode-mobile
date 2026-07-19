# Slash & @ Commands — Progress

Linear: [OPE-56](https://linear.app/opencode-mobile/issue/OPE-56/slash-and-commands) /
[OPE-59](https://linear.app/opencode-mobile/issue/OPE-59/mention-action)

## Shared planning

- [x] Plan, decisions, and parity docs for OPE-57
- [x] Design-system composer suggestion overlay contract + galleries (iOS/Android)
- [x] OPE-59 refined plan / decisions / parity (grill)

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

## Remaining

- [ ] OPE-58 — Slash actions (after OPE-59)
