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
- [ ] Complete OPE-70 (active frontier)
- [ ] Complete OPE-71
- [ ] Complete OPE-72
- [x] Update README CI table once workflows exist

## Design-system dependencies

None.

## iOS implementation

Intentionally not started. See [`parity-gaps.md`](parity-gaps.md).

## Android implementation

OPE-70 is active. Google Cloud/Firebase, the Play app draft, separate service
accounts, the upload keystore, and the protected GitHub environment/secrets are
provisioned. Gradle CI-only release signing and the manual `Android Release`
workflow are implemented on the OPE-70 branch. A local signed AAB and APK build
passed, both artifacts matched the expected upload certificate, and debug plus
missing/invalid-input behavior was validated. Protected `main` workflow runs
remain pending.

## Blockers

- The release workflow must merge to `main` before its two protected validation
  runs and first Play App Signing upload can occur.

## Remaining work

1. Merge the workflow and run it twice from protected `main`.
2. Upload the first verified AAB, accept Play App Signing, record the generated
   app-signing fingerprints and recovery owner, and leave the release
   unpublished.
3. Continue OPE-71, then OPE-72.

Deferred hardening before broader distribution: add a second trusted
administrator and optionally place another encrypted credential copy on an
offline device or removable drive. The controlled local credentials folder and
an independently encrypted off-device cloud backup are in place.
