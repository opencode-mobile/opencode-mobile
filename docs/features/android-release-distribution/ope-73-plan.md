# OPE-73 — Firebase App Distribution On Every Merge

Linear: [OPE-73](https://linear.app/opencode-mobile/issue/OPE-73/distribute-every-merged-android-build-through-firebase-app)
Parent: [OPE-69](https://linear.app/opencode-mobile/issue/OPE-69/android-release-distribution)
Companion visual review: [`ope-73-plan.html`](ope-73-plan.html).

## Outcome

Every successful merge to `main` automatically produces one signed Android APK
and distributes it to a private Firebase App Distribution tester group. The
path is independent from the manually dispatched Play internal release and
Firebase Test Lab workflows.

## Boundary

OPE-73 includes:

- a GitHub Actions workflow triggered by every `push` to `main`;
- one signed APK built from the exact merged commit;
- direct Firebase App Distribution without a Play-track publication;
- a private tester group, initially containing only the owner/tester;
- merge-commit release notes and durable workflow links;
- a dedicated least-privilege App Distribution service account and protected
  GitHub secret; and
- operator documentation and one real post-merge acceptance run.

OPE-73 does not include:

- a second Play testing-track release or a change to the canonical manual
  Android Release workflow;
- AAB distribution, public invite links, or unrestricted tester enrollment;
- an App Distribution runtime SDK, in-app update alerts, or feedback UI;
- Crashlytics, Analytics, Test Lab, FCM, or Remote Config scope changes;
- PR-triggered distribution or credentials exposed to pull requests; or
- iOS App Distribution, TestFlight, or App Store work.

## Why APK

The first real AAB distribution run proved that Firebase rejects AAB delivery
while the linked Play app remains an unreviewed draft. Making Play accept that
path would require completing store-policy declarations and submitting the app
for review, which is outside this private merge-distribution scope. Direct APK
delivery avoids an unintended Play publication boundary.

The APK is signed with the existing upload key. That certificate is distinct
from the normal Play app-signing key, so the first Firebase tester install
requires uninstalling the Play-internal build. Subsequent App Distribution
APKs share the upload-key certificate and can update within that tester channel.

This workflow reserves
`1000000000 + github.run_number` for App Distribution, separate from the
canonical Play release workflow's low run-number sequence. Moving between the
Play-internal and App Distribution channels requires uninstall/reinstall
because their APK certificates differ. The APK is not promoted to an internal,
closed, open, or production Play track.

## Trigger And Failure Contract

- Trigger on every `push` to `main`; a merged pull request naturally produces
  one qualifying push.
- Ignore direct pushes only through branch protection, not workflow logic.
- Serialize runs without canceling older queued or active runs so every merged
  commit is distributed.
- Run through the existing protected `android-release` GitHub Environment.
- A build, signature, authentication, upload, or tester-assignment failure
  fails the workflow but does not revert the merge.
- Do not trigger from pull requests, arbitrary branches, tags, or manual
  dispatch.

## Identity And Secret Contract

Create a dedicated service account such as
`opencode-app-distribution@opencode-mobile-release.iam.gserviceaccount.com`
with only the Firebase App Distribution Admin role. Store its JSON key in the
protected `android-release` environment as
`FIREBASE_APP_DISTRIBUTION_SERVICE_ACCOUNT_JSON`.

Do not reuse the Test Lab or Play publisher identity. Do not commit or print
the JSON key. Decode it into the runner temporary directory, expose its path
through `GOOGLE_APPLICATION_CREDENTIALS`, and delete it in an `always()` step.

The existing upload key signs the distributed APK. Firebase stores and serves
that signed artifact without routing it through Google Play.

## Tester Contract

Create a private Firebase App Distribution group with alias
`android-internal`. Add only the owner's tester account initially. Do not
create a public invite link.

The workflow targets the group alias, never a tester email stored in the
repository. Tester additions and removals remain console/operator actions.
The owner must remove the Play-internal installation before installing the
first App Distribution release. This is a one-time channel migration, not an
in-place update.

## Checkpoint 1 — Owner Setup

- [x] Link Firebase Android app `dev.opencode.mobile` to its existing Google
      Play app and enable App Distribution AAB delivery.
- [x] Create the private `android-internal` tester group and add the initial
      owner/tester.
- [x] Create the dedicated App Distribution service account with only Firebase
      App Distribution Admin.
- [x] Create one JSON key for that service account, preserve the controlled
      recovery copy, and add it to the protected GitHub environment as
      `FIREBASE_APP_DISTRIBUTION_SERVICE_ACCOUNT_JSON`.
- [x] Confirm no new public invite link, billing choice, or runtime SDK is
      introduced.

Linking Firebase to Google Play and creating a new service identity/key are
external account and credential changes. They require explicit owner approval
before execution.

## Checkpoint 2 — Agent Implementation

- [x] Add an `Android App Distribution` workflow triggered only by pushes to
      `main`.
- [x] Build the APK from the merged commit with CI-provided signing values.
- [x] Verify the APK signature before upload.
- [x] Authenticate with the dedicated service-account key from the protected
      environment.
- [x] Upload the APK to Firebase App Distribution for group
      `android-internal`.
- [x] Use the merge commit subject and short SHA as content-safe release notes.
- [x] Put the Firebase console and tester URLs in the workflow summary without
      printing credentials or expiring binary-download URLs.
- [x] Serialize without cancellation so a later merge cannot suppress an
      earlier merge's distribution.
- [x] Delete decoded signing and Google credentials in `always()` cleanup.
- [x] Update the operator runbook, parent plan, progress, parity gap, and Linear.

## Checkpoint 3 — Verification

- [x] Workflow structure proves that PRs and non-`main` refs cannot access the
      protected secrets.
- [x] Focused workflow syntax and release APK/signature checks pass.
- [ ] A merged commit automatically starts exactly one App Distribution run.
- [ ] Firebase shows the expected commit/version and `android-internal` group.
- [ ] The owner receives or can open the tester release and installs the
      signed Firebase build on the physical Android device.
- [ ] The App Distribution build does not publish a new Play testing-track
      release or trigger Test Lab.
- [ ] Durable acceptance evidence is recorded without tester identifiers or
      raw dashboard exports.

## Acceptance

OPE-73 is complete only when every merge to protected `main` automatically
reaches the private Firebase tester group through a signed APK, the workflow uses a
dedicated protected identity, one real merged build is installed through the
tester flow, and Play-track publication, Test Lab, runtime SDKs, PR credentials,
iOS, and public tester access remain unchanged.

## Primary References

- [Firebase App Distribution](https://firebase.google.com/docs/app-distribution)
- [Distribute Android apps using the Firebase CLI](https://firebase.google.com/docs/app-distribution/android/distribute-cli)
- [Firebase App Distribution service-account authentication](https://firebase.google.com/docs/app-distribution/authenticate-service-account)
