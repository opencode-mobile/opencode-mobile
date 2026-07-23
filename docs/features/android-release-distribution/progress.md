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
- [x] OPE-71 refined in `ope-71-plan.md`
- [x] OPE-72 refined in `ope-72-plan.md`
- [x] Complete OPE-70
- [x] Complete OPE-71
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
2. Play accepted the version-2 AAB, activated App Signing, and left that
internal-testing release as a draft for the OPE-71 handoff.

OPE-71 implementation and protected-main cloud acceptance are complete. Android
Release run
[29991719142](https://github.com/opencode-mobile/opencode-mobile/actions/runs/29991719142)
published `0.1.0-internal.4` / `versionCode` 4 to Play internal testing. Test Lab
run
[29993535229](https://github.com/opencode-mobile/opencode-mobile/actions/runs/29993535229)
passed the signed APK on `MediumPhone.arm` API 36 and API 35. The owner joined
the internal test, then confirmed that the Play-delivered build installed and
opened successfully on a Pixel 9. OPE-71 is complete.

OPE-72 console setup and branch implementation are complete. Google Analytics
is linked through the dedicated `OpenCode Mobile` account with Germany as the
reporting location and all optional account sharing disabled. The registered
Firebase app configuration matches `dev.opencode.mobile` and contains no
service-account credential or private key. The Android implementation adds
release-only Crashlytics and minimal Analytics, removes advertising and install
referrer permissions, keeps debug Firebase-free, and exposes a protected
`verify_crashlytics` workflow input for a one-shot non-fatal per version.
Focused JVM tests, debug assembly, release compilation, dependency inspection,
and merged-manifest/package inspection pass locally.

The first protected-main acceptance run
[30009973764](https://github.com/opencode-mobile/opencode-mobile/actions/runs/30009973764)
successfully published `0.1.0-internal.5` / `versionCode` 5 to Play internal
testing with Crashlytics verification enabled. Physical-device launch exposed
an existing Android startup compatibility defect before the controlled
non-fatal could run: `OutputStream.nullOutputStream()` requires API 33 while
the app supports API 24+. Firebase received the fatal for the expected release,
and Android lint reproduces the same `NewApi` failure. OPE-74 owns the fix and
replacement release.

## Blockers

OPE-72 is blocked by
[OPE-74](https://linear.app/opencode-mobile/issue/OPE-74/fix-android-startup-crash-on-api-below-33).
A replacement protected-main build must launch successfully before the
controlled non-fatal and minimal Analytics boundary can be accepted.

## Remaining work

1. Complete OPE-74 and publish a replacement protected-main build with
   `verify_crashlytics=true`.
2. Install the Play-internal build and confirm the one-shot non-fatal plus the
   minimal Analytics boundary in Firebase.
3. Record the replacement workflow, Play version, and Firebase result; then
   close OPE-72. OPE-69 remains open for OPE-73.

Deferred hardening before broader distribution: add a second trusted
administrator and optionally place another encrypted credential copy on an
offline device or removable drive. The controlled local credentials folder and
an independently encrypted off-device cloud backup are in place.
