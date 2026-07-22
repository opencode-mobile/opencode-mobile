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
- [x] Complete OPE-70
- [ ] Complete OPE-71
- [ ] Complete OPE-72
- [x] Update README CI table once workflows exist

## Design-system dependencies

None.

## iOS implementation

Intentionally not started. See [`parity-gaps.md`](parity-gaps.md).

## Android implementation

OPE-70 is complete. Google Cloud/Firebase, the Play app draft, separate service
accounts, upload-key custody, and protected GitHub environment/secrets are
provisioned. Gradle CI-only release signing and the manual `Android Release`
workflow are merged to `main`. Two protected `main` workflow runs produced
signature-verified AAB/APK artifacts with increasing `versionCode` values 1 and
2. Play accepted the version-2 AAB, activated App Signing, and retains the
internal-testing release as a draft with no rollout.

## Blockers

None for OPE-70.

## Remaining work

1. Continue OPE-71, then OPE-72.

Deferred hardening before broader distribution: add a second trusted
administrator and optionally place another encrypted credential copy on an
offline device or removable drive. The controlled local credentials folder and
an independently encrypted off-device cloud backup are in place.
