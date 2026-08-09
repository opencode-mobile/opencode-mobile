# OPE-102 — Distribute Every Merged iOS Build Through Firebase App Distribution

Linear: [OPE-102](https://linear.app/opencode-mobile/issue/OPE-102/distribute-every-merged-ios-build-through-firebase-app-distribution)
Parent: [OPE-100](https://linear.app/opencode-mobile/issue/OPE-100/ios-release-distribution)

## Outcome

Every successful merge to `main` automatically produces one signed **Ad Hoc**
iOS IPA and distributes it to a private Firebase App Distribution tester group
(`ios-internal`). The path is independent from the manually dispatched
**iOS Release** / TestFlight Internal workflow (OPE-103).

Mirrors Android [OPE-73](../android-release-distribution/ope-73-plan.md).

## Boundary

OPE-102 includes:

- a GitHub Actions workflow triggered by every `push` to `main`;
- one signed Ad Hoc IPA built from the exact merged commit;
- direct Firebase App Distribution upload (not TestFlight, not App Store);
- private tester group alias `ios-internal`;
- merge-commit release notes and durable workflow summary links;
- reuse of the existing App Distribution service account (or the same JSON in
  `ios-release`) plus ASC API signing secrets;
- Ad Hoc device/provisioning documentation; and
- operator documentation and one real post-merge acceptance install.

OPE-102 does not include:

- changing the manual `iOS Release` / TestFlight upload workflow;
- App Store or TestFlight publication as a side effect;
- External TestFlight, App Review, or public listing;
- Crashlytics / Analytics / app-side Firebase SDK;
- PR-triggered distribution or credentials on pull requests;
- Android App Distribution changes.

## Why Ad Hoc

Firebase App Distribution for iOS accepts a directly uploaded IPA. App Store /
TestFlight builds are the wrong channel for automatic merge distribution and
would couple every merge to ASC processing. Ad Hoc matches the Android pattern
of a private certificate-bound tester channel outside the store listing.

Ad Hoc requires registered device UDIDs in the provisioning profile. Testers
outside that device set cannot install until their UDID is added and a new
build is cut.

## Trigger And Failure Contract

- Trigger on every `push` to `main`.
- Serialize with `concurrency.cancel-in-progress: false` so every merge is
  distributed.
- Run through the protected `ios-release` GitHub Environment (`main` only).
- Failures fail the workflow but do not revert the merge.
- Do not trigger from pull requests, other branches, tags, or
  `workflow_dispatch`.

## Identity And Secret Contract

Reuse the dedicated App Distribution service account already used by Android:

`opencode-app-distribution@opencode-mobile-release.iam.gserviceaccount.com`

Store / mirror its JSON in the protected `ios-release` environment as
`FIREBASE_APP_DISTRIBUTION_SERVICE_ACCOUNT_JSON` (same value as
`android-release`).

Reuse existing ASC API secrets in `ios-release` for archive/export signing:

- `ASC_ISSUER_ID`
- `ASC_KEY_ID`
- `ASC_API_KEY_P8`

Do not expose these to PR Checks / simulator Build.

## Versioning

| Field | Value |
| --- | --- |
| Marketing (`CFBundleShortVersionString`) | Fixed `0.1.0` for merge builds (ASC-compatible integers) |
| Build (`CFBundleVersion`) | `1000000000 + github.run_number` (separate from TestFlight run numbers) |

This keeps App Distribution builds from colliding with the low TestFlight
`github.run_number` sequence used by **iOS Release**.

## Tester Contract

- Private Firebase group alias: `ios-internal`.
- Initial tester: owner only.
- No public invite link.
- Workflow targets the group alias, never a tester email in the repo.
- First install requires an Ad Hoc-capable device UDID present in the profile.

## Decisions locked for this plan

1. **Separate workflow** `.github/workflows/ios-app-distribution.yml` (do not
   overload manual iOS Release).
2. **Ad Hoc export** via `.github/ios/ExportOptions-AdHoc.plist`.
3. **Same Firebase project / iOS app ID** already registered:
   `1:569439646238:ios:2ffd276bbaf8a1528df40f`.
4. **No TestFlight / ASC upload** in this workflow.
5. **Stay private** — no App Review submit, no External groups.

## Checkpoint 1 — Human prep (`ready-for-human`)

- [x] Create private Firebase App Distribution group alias `ios-internal` and
      add the owner as the initial tester (no public invite link).
- [x] Confirm the verification device UDID is registered on the Apple Developer
      team (or register it) so Ad Hoc provisioning can include it.
- [x] Add `FIREBASE_APP_DISTRIBUTION_SERVICE_ACCOUNT_JSON` to the GitHub
      Environment `ios-release` (same JSON as Android `android-release`).
- [x] Confirm existing ASC API secrets remain present in `ios-release`.

Checkpoint complete when the agent can sign Ad Hoc for the owner device and
upload to `ios-internal` without new secrets in PR workflows. **Met.**

## Checkpoint 2 — Agent implementation (`ready-for-agent`)

- [x] Add `ios-app-distribution.yml`: `push` to `main`, `ios-release`
      environment, concurrency without cancellation.
- [x] Archive Release for generic iOS device with ASC API auth + persistent
      signing keychain pattern from iOS Release.
- [x] Export Ad Hoc IPA; verify Team ID + bundle ID.
- [x] Distribute with `firebase-tools` `appdistribution:distribute` to
      `ios-internal`; release notes = merge subject + short SHA.
- [x] Write Firebase console + tester links to `$GITHUB_STEP_SUMMARY` without
      printing credentials.
- [x] Upload IPA artifact (7-day retention); cleanup temp credentials always.
- [x] Keep PR Checks / Build credential-free.
- [x] Update operator runbook, decisions, progress, parity gaps, Linear.

## Checkpoint 3 — Protected verification

- [x] Merge workflow to `main` so the merge itself (or the next merge) triggers
      exactly one App Distribution run.
- [x] Firebase shows the expected commit/build for group `ios-internal`.
- [x] Owner installs the Ad Hoc build via the Firebase tester flow on a
      physical device.
- [x] Confirm no TestFlight / App Store publication side effect; App Store
      remains Prepare for Submission.
- [x] Record durable evidence (Actions URL, Firebase release link) without
      tester PII.

## Acceptance

OPE-102 is complete only when every merge to protected `main` automatically
reaches `ios-internal` through a signed Ad Hoc IPA, one real device install
succeeds, TestFlight/App Store remain unchanged, and PRs stay credential-free.

## Verification ledger

| Layer | Required evidence |
| --- | --- |
| Workflow contract | `push`/`main` only, protected env, no PR trigger, no cancel |
| Signing | Ad Hoc IPA verifies Team `677MTXPXZZ` / bundle `dev.opencode.mobile` |
| Firebase | Upload to `ios-internal` with merge notes + summary links |
| Scope safety | No TestFlight upload, no App Review, no Android changes |
| Device | Owner installs Ad Hoc build from Firebase tester experience |
| Secrets | Reuses `ios-release` ASC + App Distribution JSON only |

## Protected-main evidence

| Field | Value |
| --- | --- |
| Merge | [PR #116](https://github.com/opencode-mobile/opencode-mobile/pull/116) → `e584a2f` on `main` |
| Actions | [iOS App Distribution #1](https://github.com/opencode-mobile/opencode-mobile/actions/runs/31252901264) — success |
| Marketing / build | `0.1.0` / `1000000001` |
| Firebase | [Release](https://console.firebase.google.com/project/opencode-mobile-release/appdistribution/app/ios:dev.opencode.mobile/releases/10n7be4jvnrb0?utm_source=firebase-tools) → group `ios-internal` |
| Device | Owner installed via [tester experience](https://appdistribution.firebase.google.com/testerapps/1:569439646238:ios:2ffd276bbaf8a1528df40f/releases/10n7be4jvnrb0?utm_source=firebase-tools) (2026-08-08) |
| Store side effects | Workflow does not call `altool` / App Review; App Store listing unchanged (Prepare for Submission) |

## Risks

| Risk | Mitigation |
| --- | --- |
| Missing UDID → Ad Hoc install fails | Human registers device before first acceptance |
| Profile/device mismatch after new tester | Document UDID add + re-run distribute |
| Secret missing in `ios-release` | Fail before archive with clear message |
| Build number collision with TestFlight | Use `1000000000 + run_number` for App Distribution |

## Out of scope reminders

- TestFlight / App Store / App Review
- Crashlytics / Analytics SDK
- Public invite links
- Changing merge-gate iOS Checks / Build
