# Android Release Distribution — Progress

## Shared planning

- [x] High-level plan and open decisions drafted under
      `docs/features/android-release-distribution/`
- [x] Linear parent: [OPE-69](https://linear.app/opencode-mobile/issue/OPE-69/android-release-distribution)
- [x] Child tickets published:
      [OPE-70](https://linear.app/opencode-mobile/issue/OPE-70/provision-google-projects-and-ci-release-signing)
      → [OPE-71](https://linear.app/opencode-mobile/issue/OPE-71/publish-to-play-internal-and-run-test-lab-robo)
      → [OPE-72](https://linear.app/opencode-mobile/issue/OPE-72/ship-crashlytics-and-minimal-analytics-on-release-builds)
- [x] Grilled decisions accepted in `decisions.md`, including personal
      ownership, package-risk acceptance, signing/authentication, workflow
      boundaries, release observability, and Test Lab cost controls
- [x] OPE-70 refined in `ope-70-plan.md`
- [ ] Complete OPE-70 (frontier)
- [ ] Complete OPE-71
- [ ] Complete OPE-72
- [ ] Update README CI table once workflows exist

## Design-system dependencies

None.

## iOS implementation

Intentionally not started. See [`parity-gaps.md`](parity-gaps.md).

## Android implementation

Not started. Frontier is OPE-70 checkpoint 1 (human account, console, key, and
secret provisioning). OPE-70 then moves through agent CI implementation and a
final human Play App Signing enrollment checkpoint.

## Blockers

- OPE-70 requires human Google / Play / Firebase console setup, local upload-key
  generation, service-account provisioning, and GitHub secret configuration
  before agent implementation can start.

## Remaining work

OPE-70 → OPE-71 → OPE-72. Slice done when signed release infrastructure is
verified, one bounded Robo run succeeds, one internal-track install succeeds,
and Crashlytics sees a release build while debug/local telemetry stays clean.
