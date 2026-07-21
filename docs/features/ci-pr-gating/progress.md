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

## Remaining

- [ ] After merge: add repository ruleset requiring `android-checks` and
  `ios-checks` (see `ruleset.md`)
- [ ] Follow-up: make `ktlintCheck` / `lintDebug` hard-fail once green on main
