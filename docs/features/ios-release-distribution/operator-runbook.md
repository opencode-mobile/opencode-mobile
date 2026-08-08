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

Recovery copies of `.p8` and `GoogleService-Info.plist` live in the owner-only
`opencode-mobile-release-credentials` folder. Never commit them.

## GitHub secrets (`ios-release`)

| Secret | Purpose |
| --- | --- |
| `ASC_ISSUER_ID` | App Store Connect API Issuer ID |
| `ASC_KEY_ID` | App Store Connect API Key ID |
| `ASC_API_KEY_P8` | Raw `.p8` PEM for key `ios-release-ci` |

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
4. Wait for archive, App Store IPA export, signature verification, **ASC
   upload**, and artifact retention.
5. Download `ios-ipa-…` and `ios-xcarchive-…` artifacts if needed (7-day
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
standard system HTTPS/TLS (and ATS-allowed cleartext to user-managed LAN
servers), not custom encryption. If that assumption changes, update the plist
key and/or answer the ASC export-compliance questionnaire before the next
upload.

## Extension points

- **OPE-102** — build/distribute Ad Hoc IPAs to Firebase App Distribution on
  every `main` merge.
