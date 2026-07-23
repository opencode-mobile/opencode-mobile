# Android Release Distribution — Decisions

All decisions below were accepted during the OPE-70 refinement on 2026-07-22.

## Google ownership is personal for the private phase

**Status:** Accepted
**Decision:** A personal Google account owns Play Console, Google Cloud, and
Firebase for the private/internal phase. A second trusted administrator is
optional and deferred while the project remains private. The solo-owner
recovery risk is accepted for now; add another administrator before broader
distribution or use Google's formal transfer process if ownership later moves
to an organization.
**Why:** This is the fastest path for a solo private release. Deferring a second
administrator accepts an account-recovery risk, so the boundary is recorded
rather than treated as completed redundancy.

## Package name stays `dev.opencode.mobile`

**Status:** Accepted
**Decision:** Keep `applicationId` and the Play package name
`dev.opencode.mobile`.
**Risk acceptance:** This namespace is not evidence of upstream authorization
and could conflict with a future official OpenCode app. The risk is accepted
for this project. The Play listing must identify the app as
**OpenCode Mobile — Unofficial**, use the real developer identity, and state
that the app is independent and not endorsed by the OpenCode maintainers. Do
not claim official status or reuse upstream logos without permission.
**Why:** The package is already used by the app and becomes effectively
permanent after the first Play upload.

## One Cloud project, separate service accounts

**Status:** Accepted
**Decision:** Use one GCP project linked to one Firebase project. Create one
dedicated service account for Firebase Test Lab and a separate least-privilege
service account for Play publishing. The Test Lab account's initially narrow
role set may be broadened only when the accepted results-bucket contract below
requires it.
**Why:** One project keeps billing and administration simple; separate
identities keep Test Lab and publishing credentials isolated.

## JSON service-account credentials first

**Status:** Accepted
**Decision:** Store each service account's JSON credential as a distinct GitHub
Actions secret. Revisit Workload Identity Federation as a hardening follow-up
after both distribution paths work.
**Why:** JSON credentials are the simplest first integration for a private
project. Least privilege, separate accounts, secret storage, and rotation limit
the initial risk.

## Upload key is generated and backed up outside CI

**Status:** Accepted
**Decision:** Generate the RSA upload keystore locally on a trusted machine,
keep the owner-only `opencode-mobile-release-credentials` folder as the
controlled primary recovery copy, and keep an independently encrypted
off-device cloud backup. Store only the encoded keystore and passwords in
GitHub Actions secrets. A separate offline copy remains optional hardening
before broader distribution. CI consumes the key but never creates it or
becomes its sole holder.
**Why:** The encrypted off-device copy protects against loss of the primary
machine. It is not an offline backup, so a separately stored removable copy can
still improve resilience. Committing or publishing the key would expose the
release identity.

## Play App Signing uses Google-generated app-signing keys

**Status:** Accepted
**Decision:** CI signs AABs with the locally owned upload key. On the first
manual AAB upload, accept Play App Signing with Google-generated app-signing
keys. Google holds the app-signing key; this project retains the upload key.
**Why:** It separates the replaceable upload credential from the durable key
used for device-delivered builds.

## OPE-70 uses a human → agent → human handoff

**Status:** Accepted
**Decision:** Keep account provisioning and CI release signing in one ticket
with three checkpoints:

1. Human setup creates the accounts, projects, app draft, upload key, service
   accounts, and GitHub secrets.
2. Agent implementation wires Gradle and the protected manual workflow and
   produces verified signed AAB/APK artifacts.
3. Human completion manually uploads the first AAB, accepts Play App Signing,
   grants the publisher service account access, and leaves the release
   unpublished.

OPE-70 remains `ready-for-human` until the first checkpoint is complete, then
becomes `ready-for-agent`. OPE-71 owns automated internal-track publication.
**Why:** A new Play app cannot finish signing enrollment before an
upload-key-signed bundle exists.

## One canonical release workflow owns Play-bound versions

**Status:** Accepted
**Decision:** OPE-70 creates one manual Android Release workflow. OPE-71 extends
that workflow with Play internal-track upload and creates a separate manual
Firebase Test Lab workflow. The two OPE-71 paths remain independently
triggerable and neither becomes a PR merge gate.
**Why:** A single Play-bound workflow prevents competing `versionCode`
sequences while Test Lab stays independent from publishing.

## Signed workflows run from protected `main` only

**Status:** Accepted
**Decision:** Release-signing and Google credentials are scoped through a
GitHub Environment such as `android-release` and are available only to manual
runs from protected `main`. Pull requests and arbitrary branches do not receive
release secrets.
**Why:** Untrusted or unreviewed build logic must not access the upload key or
Google credentials.

## CI owns `versionCode`; the operator owns `versionName`

**Status:** Accepted
**Decision:** The canonical Android Release workflow derives a monotonically
increasing `versionCode` from its GitHub workflow run number. Its required
`versionName` input must match semantic-version form
`MAJOR.MINOR.PATCH[-PRERELEASE]`, for example `0.1.0` or
`0.1.0-internal.1`.
**Why:** Play receives an increasing machine value while humans control the
meaningful visible version. Keeping one Play-bound workflow makes its sequence
authoritative.

## Artifact split and retention

**Status:** Accepted
**Decision:** CI produces an upload-key-signed AAB for Play and an
upload-key-signed APK for Test Lab. GitHub retains the artifacts for seven days.
Signature verification is required before upload.
**Why:** The AAB matches Play's release format, the APK matches the initial
Test Lab path, and short retention limits stale internal artifacts and storage.

## Firebase product scope is ticketed explicitly

**Status:** Accepted
**Decision:**

- OPE-70 registers `dev.opencode.mobile` with Firebase but adds no
  `google-services.json`, Google Services Gradle plugin, or Firebase SDK.
- OPE-71 uses Test Lab and Play distribution without adding a Firebase SDK.
- OPE-72 adds Crashlytics and deliberately minimal Analytics only to
  release/Play-internal builds. Debug/local builds stay clean.
- FCM and Remote Config remain out of scope.

**Why:** Test Lab does not require an app-side Firebase SDK, while crash
reporting is a later, separately verifiable release concern.

## Release Analytics uses a dedicated minimal account

**Status:** Accepted on 2026-07-23
**Decision:** Link the Firebase project to a new Google Analytics account named
`OpenCode Mobile`, use Germany as its reporting location, and disable every
optional account data-sharing setting. In app code, keep Analytics release-only
and accept only remaining automatic lifecycle/technical events for Crashlytics
breadcrumbs. Disable automatic screen reporting, Advertising ID collection,
ad personalization, custom events, app-assigned user IDs, and app-defined user
properties.
**Why:** A dedicated account keeps this private release's data boundary
separate and explicit. The narrow event contract supplies Crashlytics
breadcrumbs without turning OPE-72 into product analytics.

## Play distribution starts with one internal tester

**Status:** Accepted
**Decision:** Use the Play internal testing track, initially limited to the
owner's verified tester account. Internal App Sharing is only a manual escape
hatch. Public, open, closed, and production tracks are follow-ups.
**Why:** This proves Play-delivered installation and updates with the smallest
possible audience.

## Test Lab starts with a bounded Robo crawl

**Status:** Accepted
**Decision:** OPE-71 runs Robo manually on Android virtual devices only, with a
maximum of two device/API combinations and a ten-minute timeout per device. It
does not add Espresso/Compose instrumentation or become a merge gate.
**Why:** This proves the cloud-device path with explicit cost controls before
expanding coverage.

## Test Lab uses the Firebase-provided default results bucket

**Status:** Accepted on 2026-07-23
**Decision:** Grant the dedicated Test Lab service account the project Editor
role, as required by the
[Firebase Test Lab CLI contract](https://docs.cloud.google.com/sdk/gcloud/reference/firebase/test/android/run),
so the protected workflow can write to Firebase Test Lab's generated default
results bucket. Retain the narrower Test Lab, Analytics Viewer, Quality Admin,
and Storage Object Admin roles for an explicit capability inventory. Revisit
the broad Editor grant before distribution expands beyond the private phase.
**Why:** The generated default bucket rejected object creation under the
narrower project roles and is not exposed as a project-owned bucket with a
directly editable bucket policy. A custom results bucket would provide a
narrower storage boundary but introduces separately billed Cloud Storage. The
owner explicitly accepted Editor for this private release slice.

## iOS remains a documented no-op

**Status:** Accepted
**Decision:** No Apple Developer, TestFlight, App Store Connect, or iOS signing
work is included. Start a separate iOS release-distribution slice when an Apple
Developer account exists.
**Why:** Android distribution should not wait on unavailable Apple account
infrastructure. The intentional difference remains recorded in
[`parity-gaps.md`](parity-gaps.md).

## Separate Firebase App Distribution install channel

**Status:** Accepted for OPE-73 implementation

**Decision:** Distribute AABs through Firebase App Distribution and Google Play
internal app sharing. Treat this as a separate install channel from the Play
internal testing track. The owner uninstalls the Play-internal build once before
installing the first Firebase build; later Firebase builds update within that
channel.

**Why:** Google Play re-signs internal-app-sharing APKs with a dedicated test
certificate that differs from the normal Play app-signing certificate. An
in-place update across those certificates is not possible. AAB delivery still
provides device-specific APKs and a stable certificate for subsequent private
tester updates.

**Versioning:** Use `1000000000 + github.run_number` for the App Distribution
workflow. The reserved high range is monotonic inside this channel and remains
separate from the canonical Play release workflow's version sequence.

**Revisit when:** Firebase supports delivery with the normal Play app-signing
certificate or the project replaces App Distribution with a Play testing-track
release on every merge.
