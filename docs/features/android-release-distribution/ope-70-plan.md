# OPE-70 — Provision Google Projects And CI Release Signing

Linear: [OPE-70](https://linear.app/opencode-mobile/issue/OPE-70/provision-google-projects-and-ci-release-signing)
Parent: [OPE-69](https://linear.app/opencode-mobile/issue/OPE-69/android-release-distribution)

## Outcome

Prepare the human-owned Google infrastructure and a protected CI path that
produces verified upload-key-signed AAB and APK artifacts for
`dev.opencode.mobile`. Finish Play App Signing enrollment without publishing an
internal release. OPE-71 consumes this foundation.

## Boundary

OPE-70 includes Google/Firebase/Play provisioning, upload-key custody, service
accounts, Gradle release signing, the canonical Android Release workflow, and
the first manual AAB upload needed for Play App Signing enrollment.

It does not publish to the Play internal track, run Firebase Test Lab, add
`google-services.json` or any Firebase SDK, or add Crashlytics/Analytics. Those
belong to OPE-71 and OPE-72.

## Checkpoint 1 — Human setup (`ready-for-human`)

- [x] Create or verify the personal Play developer account with truthful
      developer identity and two-step verification.
- [x] Record the private-phase ownership posture: a second trusted
      administrator is optional/deferred and the solo-owner recovery risk is
      accepted for now.
- [x] Create one GCP project, enable billing as needed, and link one Firebase
      project.
- [x] Register the Firebase Android app for `dev.opencode.mobile`, but do not
      add app-side Firebase configuration to the repo.
- [x] Create a Play Console app draft for `dev.opencode.mobile` with the working
      title **OpenCode Mobile — Unofficial** and an explicit independent-client
      disclaimer.
- [x] Create separate least-privilege service accounts for Test Lab and Play
      publishing.
- [x] Generate an RSA upload keystore locally.
- [x] Keep the owner-only `opencode-mobile-release-credentials` folder as the
      controlled primary recovery copy and an independently encrypted archive
      in off-device cloud storage; record that a separate offline copy remains
      optional hardening before broader distribution.
- [x] Configure an `android-release` GitHub Environment restricted to protected
      `main` and add the release secrets.

Recommended secret contract:

| Secret | Purpose |
| --- | --- |
| `ANDROID_UPLOAD_KEYSTORE_BASE64` | Encoded upload keystore bytes |
| `ANDROID_UPLOAD_STORE_PASSWORD` | Upload keystore password |
| `ANDROID_UPLOAD_KEY_ALIAS` | Upload-key alias |
| `ANDROID_UPLOAD_KEY_PASSWORD` | Upload-key password |
| `GCP_TEST_LAB_SERVICE_ACCOUNT_JSON` | Test Lab service account JSON |
| `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | Play publishing service account JSON |

Record non-secret project IDs, service-account names, and administrator roles in
the operator runbook. Never record secret values or the keystore itself.

Checkpoint complete when every item above exists and the ticket can change from
`ready-for-human` to `ready-for-agent`.

## Checkpoint 2 — Agent implementation (`ready-for-agent`)

- [x] Configure the Android release build to read signing material only from CI
      inputs and fail clearly when required values are absent.
- [x] Keep release credentials out of local/debug and pull-request builds.
- [x] Add one canonical `workflow_dispatch` Android Release workflow, scoped to
      the protected `android-release` environment and `main`.
- [x] Require a semantic `versionName` input matching
      `MAJOR.MINOR.PATCH[-PRERELEASE]`.
- [x] Derive the Play-bound `versionCode` from the canonical workflow's
      monotonically increasing run number.
- [x] Build an upload-key-signed AAB and APK locally.
- [x] Verify both artifact signatures against the expected upload certificate
      before upload and ensure verification
      output contains no secret material.
- [x] Retain both artifacts for seven days.
- [x] Run the workflow twice from protected `main`: run 1 produced
      `0.1.0-internal.1` / `versionCode` 1 and run 2 produced
      `0.1.0-internal.2` / `versionCode` 2; both signed artifacts verified and
      uploaded successfully.
- [x] Document triggers, inputs, secret names, key recovery/rotation, artifact
      retrieval, and the OPE-71 extension point.

Checkpoint complete when two protected `main` runs satisfy every automated gate
and produce usable signed artifacts.

## Checkpoint 3 — Human Play enrollment (`ready-for-human`)

- [x] Download the verified AAB from the successful workflow.
- [x] Manually upload `0.1.0-internal.2` / `versionCode` 2 while preparing the
      first Play release.
- [x] Accept Play App Signing with Google-generated app-signing keys.
- [x] Grant the Play publishing service account access to the app.
- [x] Leave the internal-testing release as a draft; OPE-71 owns publication.
- [x] Record the non-secret upload-key and app-signing certificate fingerprints
      plus the personal recovery owner in the runbook.

## Completion evidence

- [Android Release run 1](https://github.com/opencode-mobile/opencode-mobile/actions/runs/29947801859)
  succeeded for `0.1.0-internal.1` / `versionCode` 1.
- [Android Release run 2](https://github.com/opencode-mobile/opencode-mobile/actions/runs/29948002212)
  succeeded for `0.1.0-internal.2` / `versionCode` 2.
- Play Console accepted the version-2 AAB, activated Google Play App Signing,
  and shows the internal-testing release as **Draft** and the bundle as
  **Inactive**. No release was rolled out.

## Acceptance

OPE-70 is complete only when:

- the personal Play/GCP/Firebase ownership and accepted private-phase
  solo-owner recovery posture are documented;
- `dev.opencode.mobile` is registered and the unofficial-branding risk is
  explicitly recorded;
- separate least-privilege Test Lab and Play service accounts exist and their
  JSON credentials are stored separately;
- the upload keystore exists only in controlled secret storage, with both the
  local primary recovery copy and independently encrypted off-device backup
  documented;
- protected `main` CI produces signature-verified AAB and APK artifacts without
  exposing credentials;
- two workflow runs prove increasing `versionCode` and validated semantic
  `versionName` values;
- Play App Signing is enrolled with Google-generated keys after the first
  manual AAB upload, with no release published; and
- the operator runbook contains the non-secret handoff information required by
  OPE-71.

## Verification Ledger

| Layer | Required evidence |
| --- | --- |
| Gradle | Release AAB and APK tasks succeed with CI-provided signing values |
| Signing | AAB and APK signatures verify against the expected upload certificate |
| Versioning | Two canonical workflow runs produce increasing codes and accepted semantic names |
| Secret safety | No keystore, password, JSON credential, or decoded temporary file enters git or uploaded artifacts |
| Play setup | First AAB is accepted, Play App Signing is active, publisher access exists, release remains unpublished |
| Documentation | Ownership, identifiers, secret names, recovery, triggers, and OPE-71 handoff are recorded |
