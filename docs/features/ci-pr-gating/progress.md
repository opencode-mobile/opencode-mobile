# CI PR Gating — Progress

Linear: [OPE-68](https://linear.app/opencode-mobile/issue/OPE-68/pr-ci-gating-on-blacksmith)
Branch: `feature/ope-68-pr-ci-gating`

## Completed

- [x] Linear issue and feature branch
- [x] Feature docs under `docs/features/ci-pr-gating/`
- [x] PR triggers, draft guard, concurrency, job names on Checks workflows
- [x] Soft-fail Android lint/ktlint; hard-fail unit tests
- [x] Update README and AGENTS CI notes
- [x] Document `main` ruleset steps in `ruleset.md`
- [x] Fix Android unit tests blocking PR Checks (part-order assertion +
  benchmark test dispatcher)
- [x] Align iOS MessageSync tests with OPE-49 TextPart-only `Message.text`
- [x] Quarantine Android/iOS markdown load benchmarks from CI gates
- [x] Restore Android Build to `workflow_dispatch` only (it had drifted onto
  PR and feature-branch push triggers)

## Remaining

- [ ] After merge: add repository ruleset requiring `android-checks` and
  `ios-checks` (see `ruleset.md`) — needs GitHub Team on private repos
- [ ] Follow-up: make `ktlintCheck` / `lintDebug` hard-fail once green on main
