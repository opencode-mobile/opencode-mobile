# Android Release — Operator Runbook

This runbook records the non-secret ownership and operating contract for
OPE-70. Never add a keystore, password, service-account JSON, or decoded secret
to this repository, workflow artifacts, logs, or issue comments.

## Infrastructure inventory

| Resource | Non-secret identifier | Owner / access |
| --- | --- | --- |
| Google Cloud / Firebase project | `opencode-mobile-release` (project number `569439646238`) | Personal Google owner; second administrator optional/deferred for the private phase; solo-owner recovery risk accepted |
| Firebase Android app | `dev.opencode.mobile` | Same Firebase project; no app-side Firebase configuration in OPE-70 |
| Play developer account | `RawPotato` (developer ID `5676612005420685527`) | Personal owner; legal identity, contact methods, and two-step verification confirmed |
| Play app draft | **OpenCode Mobile — Unofficial** (app ID `4974264711668360153`) | Draft only; independent-client disclaimer saved; not submitted for review |
| Test Lab service account | `opencode-test-lab@opencode-mobile-release.iam.gserviceaccount.com` | Firebase Quality Admin in the Cloud project |
| Play publisher service account | `opencode-play-publisher@opencode-mobile-release.iam.gserviceaccount.com` | App-scoped Play testing-track release access; no production permission |
| GitHub environment | `android-release` | Restricted to the `main` branch |

Google Analytics is disabled. OPE-70 does not add `google-services.json`, the
Google Services Gradle plugin, a Firebase SDK, Crashlytics, or Analytics.

## GitHub environment secrets

The `android-release` environment contains these encrypted secrets:

| Secret | Source |
| --- | --- |
| `ANDROID_UPLOAD_KEYSTORE_BASE64` | Base64-encoded upload keystore bytes |
| `ANDROID_UPLOAD_STORE_PASSWORD` | Upload-keystore store password |
| `ANDROID_UPLOAD_KEY_ALIAS` | Upload key alias (`upload`) |
| `ANDROID_UPLOAD_KEY_PASSWORD` | Upload-key password |
| `GCP_TEST_LAB_SERVICE_ACCOUNT_JSON` | Test Lab service-account JSON |
| `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | Play publisher service-account JSON |

GitHub does not expose existing secret values. Replace a secret to rotate it.
The release workflow decodes the keystore into the runner temporary directory,
uses environment variables rather than command-line passwords, and deletes the
temporary keystore in an `always()` cleanup step.

## Run Android Release

The workflow is manual and accepts only protected `main`:

```bash
gh workflow run "Android Release" --ref main -f version_name=0.1.0
```

`version_name` must match `MAJOR.MINOR.PATCH[-PRERELEASE]`, for example `0.1.0`
or `0.1.0-internal.1`. The workflow derives `versionCode` from the monotonically
increasing GitHub run number.

Each successful run uploads two seven-day artifacts:

- `android-aab-<version-name>-<run-number>` — signed AAB for Play;
- `android-apk-<version-name>-<run-number>` — signed APK for Test Lab.

The workflow verifies the AAB with `jarsigner` and the APK with `apksigner`
before upload. A missing secret, malformed version, signing failure, or signature
verification failure stops the run.

## Upload certificate

- SHA-1: `A2:65:2E:82:3D:44:25:E9:DD:A2:0C:DC:84:A0:32:B7:E7:DD:98:09`
- SHA-256: `9E:FB:99:19:6B:2D:F6:56:FD:FA:1D:88:3D:33:00:7E:0F:E0:DA:28:AB:FF:A6:F8:BC:C3:3D:42:0F:CA:E1:37`

## Play app-signing certificate

Play App Signing was activated by the first uploaded AAB:
`0.1.0-internal.2` / `versionCode` 2. The internal-testing release remains a
draft and the bundle is inactive; OPE-71 owns rollout/publication.

- SHA-1: `B5:19:27:43:0D:2B:12:32:84:FF:52:B3:A6:7D:9D:EE:EA:88:DA:AD`
- SHA-256: `B0:0C:45:26:2A:B6:27:47:B5:8E:51:A8:BA:10:E9:07:D8:D8:95:EF:E0:34:2E:E6:CD:93:B5:DF:B5:48:FF:A8`

The personal Google owner is the recovery owner for the private phase.

## Recovery and rotation

The owner-only `opencode-mobile-release-credentials` folder is the controlled
primary recovery copy. An independently AES-256-encrypted archive is backed up
in off-device, end-to-end encrypted cloud storage; its password is not recorded
here. This protects against loss of the primary machine, but it is not an
offline backup. Before broader distribution, optionally place another encrypted
copy on a separate offline device or removable drive and consider adding a
second trusted administrator. GitHub is a consumer, not the recovery source.
Record recovery ownership without recording secret values.

If a service-account JSON key is exposed, revoke that key in Google Cloud,
create one replacement key for the same least-privilege account, and replace
only its corresponding GitHub secret. If the upload key is exposed, follow the
Play Console upload-key reset process, replace all four upload-key secrets, and
update the non-secret certificate fingerprints.

## OPE-71 handoff

OPE-71 extends the canonical Android Release workflow to upload its AAB to the
Play internal track using `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`. It adds a separate
manual Test Lab workflow that consumes the signed APK and
`GCP_TEST_LAB_SERVICE_ACCOUNT_JSON`. Neither workflow becomes a pull-request
merge gate, and OPE-71 must not create a second Play-bound version sequence.
