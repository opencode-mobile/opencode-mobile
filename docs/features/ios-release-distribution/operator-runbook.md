# iOS Release Distribution — Operator Runbook

## Ownership

| Item | Value | Notes |
| --- | --- | --- |
| Apple Team ID | `677MTXPXZZ` | Individual Apple Developer Program; Ivan Fenenko |
| ASC app | **OpenCode Mobile — Unofficial** | Bundle `dev.opencode.mobile`, Apple ID `6799246544` |
| ASC API key | `ios-release-ci` | Admin Team key; Key ID `D9QNSV5RTZ` |
| ASC Issuer ID | `e1d81a16-b119-4477-9f70-e80021c183c7` | Shown on ASC Integrations → App Store Connect API |
| Firebase project | `opencode-mobile-release` | Same as Android |
| Firebase iOS app | `1:569439646238:ios:2ffd276bbaf8a1528df40f` | Bundle `dev.opencode.mobile` |
| GitHub Environment | `ios-release` | Custom branch policy: `main` only |

Recovery copies of `.p8` and a spare `GoogleService-Info.plist` live in the
owner-only `opencode-mobile-release-credentials` folder. The tracked app plist
at `ios/OpenCode Mobile/OpenCode Mobile/GoogleService-Info.plist` is public
client routing config (same posture as Android `google-services.json`); never
commit service-account JSON or `.p8` keys.

## GitHub secrets (`ios-release`)

| Secret | Purpose |
| --- | --- |
| `ASC_ISSUER_ID` | App Store Connect API Issuer ID |
| `ASC_KEY_ID` | App Store Connect API Key ID |
| `ASC_API_KEY_P8` | Raw `.p8` PEM for key `ios-release-ci` |
| `FIREBASE_APP_DISTRIBUTION_SERVICE_ACCOUNT_JSON` | Same App Distribution SA JSON as Android (`opencode-app-distribution@…`) |

## Versioning

| Field | Owner | Source |
| --- | --- | --- |
| Marketing version (`CFBundleShortVersionString`) | Operator | `version_name` workflow input (`MAJOR.MINOR.PATCH`, integers only) |
| Build number (`CFBundleVersion`) | CI | `github.run_number` for that iOS Release run |

TestFlight shows both (example: `0.1.0 (8)`). Never reuse a lower build number
for the same marketing version after ASC has accepted a higher one. Do not use
`-internal` / other prerelease suffixes — ASC rejects them.

## Run iOS Release (sign + TestFlight upload)

1. Merge the release changes to `main`.
2. Actions → **iOS Release** → Run workflow on `main`.
3. Set `version_name` (example: `0.1.0`).
4. Leave `verify_crashlytics` at **false** for normal releases.
5. Wait for archive, App Store IPA export, signature verification, **ASC
   upload**, and artifact retention.
6. Download `ios-ipa-…` and `ios-xcarchive-…` artifacts if needed (7-day
   retention). The workflow already uploaded the same IPA to App Store Connect.

Build number (`CFBundleVersion`) equals the workflow run number.

The archive step currently forces `SWIFT_OPTIMIZATION_LEVEL=-Onone` because
Xcode 26.x Release WMO crashes on this project. Signing remains Apple
Distribution.

### Upload accepted vs build processed

- **Upload accepted** — CI finished `xcrun altool --upload-package`
  successfully. The binary was delivered to App Store Connect.
- **Build processed** — App Store Connect finished processing (can take minutes).
  Only then does the build appear under TestFlight for Internal Testing.

CI does **not** wait for processing. That is a human follow-up.

## Install from TestFlight Internal Testing

1. Open [App Store Connect](https://appstoreconnect.apple.com) →
   **OpenCode Mobile — Unofficial** → **TestFlight**.
2. Wait until the new build leaves **Processing** and is available.
3. Confirm it is under **Internal Testing** (ASC team users only).
4. On the verification device, open the **TestFlight** app, accept the build if
   prompted, install, and launch **OpenCode Mobile — Unofficial**.

### Stay private (required)

Do **not**:

- click **Submit for Review** or release the app to the App Store;
- create or use **External** TestFlight groups for this slice;
- change the App Store version out of an unpublished draft state
  (e.g. **Prepare for Submission**).

Internal Testing does not require App Review or Beta App Review and does not
make the app publicly searchable or downloadable.

## Verify an IPA locally

```bash
.github/scripts/verify-ios-release-ipa.sh \
  /path/to/OpenCode\ Mobile.ipa \
  677MTXPXZZ \
  dev.opencode.mobile
```

## Runner signing keychain

On the `mac-mini` self-hosted runner, iOS Release keeps signing material in:

- `~/.opencode-mobile/ios-release.keychain-db`
- `~/.opencode-mobile/ios-release.keychain.password`

Do not delete these between runs. To rotate signing material: delete both files,
revoke orphaned Apple Development / Distribution certificates in the developer
portal if needed, then re-run **iOS Release** so Xcode recreates them via the
ASC API key.

## Key rotation

1. Create a new ASC Team key in Users and Access → Integrations.
2. Replace files in the credentials folder and update `ios-release` secrets.
3. Revoke the old key in App Store Connect.
4. Confirm the next iOS Release run succeeds.

## Export compliance

The app target sets `ITSAppUsesNonExemptEncryption=NO` because it uses only
standard system HTTPS/TLS (and ATS `NSAllowsArbitraryLoads` cleartext to
user-managed servers such as LAN or Tailscale HTTP), not custom encryption.
If that assumption changes, update the plist key and/or answer the ASC
export-compliance questionnaire before the next upload.

## Firebase App Distribution

`iOS App Distribution` distributes on every push to protected `main`. It also
accepts a manual `workflow_dispatch` from `main`, `feature/*`, or `fix/*`.
Leave `source_ref` empty to distribute the selected workflow branch; set it to
a branch, tag, or SHA to check out that commit instead. The `ios-release`
environment allows those same branch patterns. TestFlight stays main-only.

It archives a Release build, exports a signed **Ad Hoc** IPA, verifies Team ID
`677MTXPXZZ` / bundle `dev.opencode.mobile`, and distributes the IPA to the
private Firebase group alias `ios-internal`. It does **not** upload to
TestFlight, submit for App Review, or change the App Store listing.

```bash
gh workflow run "iOS App Distribution" --ref main
gh workflow run "iOS App Distribution" --ref feature/ope-146-server-api-v2
```

| Field | Value |
| --- | --- |
| Workflow | `.github/workflows/ios-app-distribution.yml` |
| Export options | `.github/ios/ExportOptions-AdHoc.plist` (`method=ad-hoc`) |
| Marketing version | Fixed `0.1.0` |
| Build number | `1000000000 + github.run_number` (separate from TestFlight) |
| Firebase app | `1:569439646238:ios:2ffd276bbaf8a1528df40f` |
| Tester group | `ios-internal` (no public invite link) |
| Runner / env | Self-hosted `mac-mini`; GitHub Environment `ios-release` |

The workflow reuses the persistent signing keychain and ASC API auth pattern
from **iOS Release**, including the Release WMO workaround
(`SWIFT_OPTIMIZATION_LEVEL=-Onone`). Firebase CLI `15.24.0` uses Application
Default Credentials from `FIREBASE_APP_DISTRIBUTION_SERVICE_ACCOUNT_JSON`.
Release notes are the commit subject plus short SHA (`Merge` on a `main`
push, `Dispatch` on a manual run). The workflow summary gets only the stable
Firebase console and tester-experience links. The IPA artifact is retained
for seven days.

### Install from Firebase (Ad Hoc)

1. Confirm the verification device UDID is registered under Apple Developer →
   Certificates, Identifiers & Profiles → Devices (required for Ad Hoc).
2. After a successful merge or manual dispatch run, open the tester-experience
   link from the workflow summary (or Firebase console → App Distribution →
   Releases) while signed in as the private tester.
3. Install and launch **OpenCode Mobile — Unofficial**.

Ad Hoc installs only on registered devices. Adding a new tester device requires
registering its UDID, then cutting a new App Distribution build so the profile
includes it. Switching between TestFlight and Ad Hoc on the same device may
require uninstall/reinstall when the install channels conflict.

If a distribution run fails, inspect archive, Ad Hoc export, signature
verification, and Firebase upload steps. A failed merge-triggered run does not
revert the merge. Rotate only the dedicated App Distribution key if that
credential is exposed, and replace its protected GitHub secret in both
`ios-release` and `android-release`.

## Verify release observability

Normal TestFlight releases leave the controlled verification path disabled
(`verify_crashlytics=false`).

After OPE-122 implementation is on `main`, run exactly one protected
verification release:

```bash
gh workflow run "iOS Release" \
  --ref main \
  -f version_name=<next-semantic-version> \
  -f verify_crashlytics=true
```

The workflow input flips `OpenCodeVerifyCrashlytics` in the app Info.plist for
that Release build only (app-target gate; not a global Swift compilation flag).
The app records a content-free `OpenCodeMobileCrashlyticsVerification` non-fatal
once per installed build number and stores the marker in `UserDefaults`.
Install from TestFlight Internal Testing (or an equivalent Ad Hoc App
Distribution build if that path is chosen), launch once, then relaunch so
Crashlytics can upload the batched report. Confirm the expected version, build
number, device, and exception in the Crashlytics dashboard. A third launch must
not produce another verification event for that build.

The release Analytics contract allows only Firebase's remaining automatic
lifecycle/technical events used as Crashlytics breadcrumbs. The app sends no
custom events, screen views, app-assigned user IDs, or app-defined user
properties. Automatic screen reporting and ad personalization defaults are
disabled; do not add App Tracking Transparency or `NSUserTrackingUsageDescription`
for this slice. Debug / day-to-day simulator builds do not initialize Firebase
and strip `GoogleService-Info.plist` from the Debug app bundle.

If the non-fatal does not appear, confirm the installed TestFlight build,
relaunch the app, and check Crashlytics for a successful report upload before
changing code. Do not paste raw dashboard exports, tester identifiers, app
content, or credentials into the repository or Linear.
