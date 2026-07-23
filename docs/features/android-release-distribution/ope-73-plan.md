# OPE-73 — Firebase App Distribution On Every Merge

Linear: [OPE-73](https://linear.app/opencode-mobile/issue/OPE-73/distribute-every-merged-android-build-through-firebase-app)
Parent: [OPE-69](https://linear.app/opencode-mobile/issue/OPE-69/android-release-distribution)
Companion visual review: [`ope-73-plan.html`](ope-73-plan.html).

## Outcome

Every successful merge to `main` automatically produces one Android App Bundle
and distributes it to a private Firebase App Distribution tester group. The
path is independent from the manually dispatched Play internal release and
Firebase Test Lab workflows.

## Boundary

OPE-73 includes:

- a GitHub Actions workflow triggered by every `push` to `main`;
- one AAB built from the exact merged commit;
- Firebase App Distribution through Google Play internal app sharing;
- a private tester group, initially containing only the owner/tester;
- merge-commit release notes and durable workflow links;
- a dedicated least-privilege App Distribution service account and protected
  GitHub secret; and
- operator documentation and one real post-merge acceptance run.

OPE-73 does not include:

- a second Play testing-track release or a change to the canonical manual
  Android Release workflow;
- APK distribution, public invite links, or unrestricted tester enrollment;
- an App Distribution runtime SDK, in-app update alerts, or feedback UI;
- Crashlytics, Analytics, Test Lab, FCM, or Remote Config scope changes;
- PR-triggered distribution or credentials exposed to pull requests; or
- iOS App Distribution, TestFlight, or App Store work.

## Why AAB

Firebase App Distribution integrates AAB delivery with Google Play internal app
sharing. Google Play generates device-specific APKs and signs them with its
internal-app-sharing certificate. This avoids distributing an APK signed with
the upload key, which cannot update a Play-installed app signed with the Play
app-signing key.

Internal app sharing permits reused version codes, so this workflow does not
compete with the canonical Play release workflow's version sequence. The AAB is
not promoted to an internal, closed, open, or production Play track.

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

The existing upload key may sign the AAB before upload, but Google Play internal
app sharing re-signs generated APKs with its dedicated test certificate.

## Tester Contract

Create a private Firebase App Distribution group with alias
`android-internal`. Add only the owner's tester account initially. Do not
create a public invite link.

The workflow targets the group alias, never a tester email stored in the
repository. Tester additions and removals remain console/operator actions.

## Checkpoint 1 — Owner Setup

- [ ] Link Firebase Android app `dev.opencode.mobile` to its existing Google
      Play app and enable App Distribution AAB delivery.
- [ ] Create the private `android-internal` tester group and add the initial
      owner/tester.
- [ ] Create the dedicated App Distribution service account with only Firebase
      App Distribution Admin.
- [ ] Create one JSON key for that service account, preserve the controlled
      recovery copy, and add it to the protected GitHub environment as
      `FIREBASE_APP_DISTRIBUTION_SERVICE_ACCOUNT_JSON`.
- [ ] Confirm no new public invite link, billing choice, or runtime SDK is
      introduced.

Linking Firebase to Google Play and creating a new service identity/key are
external account and credential changes. They require explicit owner approval
before execution.

## Checkpoint 2 — Agent Implementation

- [ ] Add an `Android App Distribution` workflow triggered only by pushes to
      `main`.
- [ ] Build the AAB from the merged commit with CI-provided signing values.
- [ ] Verify the AAB signature before upload.
- [ ] Authenticate with the dedicated service-account key from the protected
      environment.
- [ ] Upload the AAB to Firebase App Distribution for group
      `android-internal`.
- [ ] Use the merge commit subject and short SHA as content-safe release notes.
- [ ] Put the Firebase console and tester URLs in the workflow summary without
      printing credentials or expiring binary-download URLs.
- [ ] Serialize without cancellation so a later merge cannot suppress an
      earlier merge's distribution.
- [ ] Delete decoded signing and Google credentials in `always()` cleanup.
- [ ] Update the operator runbook, parent plan, progress, parity gap, and Linear.

## Checkpoint 3 — Verification

- [ ] Workflow structure proves that PRs and non-`main` refs cannot access the
      protected secrets.
- [ ] Focused workflow syntax and release bundle/signature checks pass.
- [ ] A merged commit automatically starts exactly one App Distribution run.
- [ ] Firebase shows the expected commit/version and `android-internal` group.
- [ ] The owner receives or can open the tester release and installs the
      Play-generated build on the physical Android device.
- [ ] The App Distribution build does not publish a new Play testing-track
      release or trigger Test Lab.
- [ ] Durable acceptance evidence is recorded without tester identifiers or
      raw dashboard exports.

## Acceptance

OPE-73 is complete only when every merge to protected `main` automatically
reaches the private Firebase tester group through an AAB, the workflow uses a
dedicated protected identity, one real merged build is installed through the
tester flow, and Play-track publication, Test Lab, runtime SDKs, PR credentials,
iOS, and public tester access remain unchanged.

## Primary References

- [Firebase App Distribution](https://firebase.google.com/docs/app-distribution)
- [Distribute Android apps using Gradle](https://firebase.google.com/docs/app-distribution/android/distribute-gradle)
- [Firebase App Distribution service-account authentication](https://firebase.google.com/docs/app-distribution/authenticate-service-account)
- [Google Play internal app sharing](https://support.google.com/googleplay/android-developer/answer/9844679)
