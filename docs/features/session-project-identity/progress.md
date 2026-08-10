# Session Project Identity Progress

## Shared planning

- [x] Prototype series (baseline through D refinements + smart disambiguation)
- [x] Lock **D2** chrome + smart project label rules ([decisions.md](./decisions.md))
- [x] Feature plan ([plan.md](./plan.md))
- [x] Design-system top-bar extension: subtitle + letter + status coexist
- [x] Shared smart-label helper + unit tests (iOS + Android)
- [x] Wire Session Chat chrome on both platforms (worktree + added-project peers)
- [ ] Manual check: unique name, one-parent collision, deeper collision, missing
      worktree fallback

## Design-system dependencies

- [x] `OpenCodeTopBar` second-line composition (`subtitleLeading.letter` +
      subtitle + optional statusDot)
- [x] Gallery control: Project meta (both platforms)
- [x] Letter accent helpers reused from home
- [x] `OpenCodeConnectionStatusLabel` status copy/dot semantics

## iOS implementation

- [x] `smartProjectLabel` + `SmartProjectLabelTests`
- [x] `OpenCodeTopBar` subtitleLeading + composed second line
- [x] `SessionChatUiState` project fields; VM loads peers from
      `ConnectionProfileStore`
- [x] `SessionChatView` top bar wiring

## Android implementation

- [x] `smartProjectLabel` + `SmartProjectLabelTest`
- [x] `OpenCodeTopBar` subtitleLeading + composed second line
- [x] `SessionChatUiState` project fields; VM loads peers from
      `ConnectionProfileStore`
- [x] `SessionChatScreen` top bar wiring
- [x] `SessionChatViewModelTest` + smart-label unit tests green

## Blockers

- None

## Remaining work

1. Manual QA on device/simulator for unique / collision / deeper paths.
2. Optional: PR + Linear remaining checklist tick after manual pass.
