# Android Release Distribution — Progress

## Shared planning

- [x] High-level plan and open decisions drafted under
      `docs/features/android-release-distribution/`
- [x] Linear parent: [OPE-69](https://linear.app/opencode-mobile/issue/OPE-69/android-release-distribution)
- [x] Child tickets published:
      [OPE-70](https://linear.app/opencode-mobile/issue/OPE-70/provision-google-projects-and-ci-release-signing)
      → [OPE-71](https://linear.app/opencode-mobile/issue/OPE-71/publish-to-play-internal-and-run-test-lab-robo)
      → [OPE-72](https://linear.app/opencode-mobile/issue/OPE-72/ship-crashlytics-and-minimal-analytics-on-release-builds)
      → [OPE-73](https://linear.app/opencode-mobile/issue/OPE-73/distribute-every-merged-android-build-through-firebase-app)
- [x] Grilled decisions accepted in `decisions.md`, including personal
      ownership, package-risk acceptance, signing/authentication, workflow
      boundaries, release observability, and Test Lab cost controls
- [x] OPE-70 refined in `ope-70-plan.md`
- [x] OPE-71 refined in `ope-71-plan.md`
- [x] OPE-72 refined in `ope-72-plan.md`
- [x] OPE-73 refined in `ope-73-plan.md`
- [x] Complete OPE-70
- [x] Complete OPE-71
- [x] Complete OPE-72
- [ ] Complete OPE-73
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
testing with Crashlytics verification enabled. Firebase received the controlled
`OpenCodeMobileCrashlyticsVerification` non-fatal for that Play release and the
Analytics event report showed no custom events or screen views.

The same launch also exposed an existing Android startup compatibility defect:
`OutputStream.nullOutputStream()` requires API 33 while the app supports API
24+. OPE-74 replaced that call with an API-24-safe sink, and pull request
[70](https://github.com/opencode-mobile/opencode-mobile/pull/70) merged the fix
to `main`. Replacement Android Release run
[30011576034](https://github.com/opencode-mobile/opencode-mobile/actions/runs/30011576034)
published `0.1.0-internal.6` / `versionCode` 6. The owner launched it repeatedly
on the affected Pixel 9; Firebase recognized version 6 as the latest active
release while the startup-crash issue remained confined to version 5. OPE-72
and OPE-74 are complete.

OPE-73 planning, repository implementation, and owner setup are active. The
`Android App Distribution` workflow triggers only on pushes to protected
`main`, signs and verifies one AAB, authenticates with a dedicated protected
secret, targets the private `android-internal` group, and summarizes only stable
Firebase links. Runs serialize without cancellation. App Distribution uses a
separate high version-code range and Google Play's internal-app-sharing
certificate, so the owner must uninstall the Play-internal build once before
installing the first Firebase release.

The owner approved and completed the external setup. Firebase is linked to the
existing Play app with AAB delivery enabled, the Internal App Sharing terms are
accepted, the private `android-internal` group contains the sole initial tester,
and the dedicated App Distribution service account has only Firebase App
Distribution Admin. Its JSON key is stored in the owner-only recovery folder
and the protected GitHub environment secret.

## Blockers

None before the first merged workflow run.

## Remaining work

1. Merge the workflow and complete one real automatic App Distribution run.
2. Migrate the Pixel 9 once from the Play-internal certificate to the internal-
   app-sharing certificate, then install and open the Firebase release.
3. Keep OPE-69 open until OPE-73 is complete.

Deferred hardening before broader distribution: add a second trusted
administrator and optionally place another encrypted credential copy on an
offline device or removable drive. The controlled local credentials folder and
an independently encrypted off-device cloud backup are in place.
