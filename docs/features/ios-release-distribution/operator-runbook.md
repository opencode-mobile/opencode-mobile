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

## Run iOS Release

1. Merge the release-signing changes to `main`.
2. Actions → **iOS Release** → Run workflow on `main`.
3. Set `version_name` (example: `0.1.0-internal.1`).
4. Wait for archive, App Store IPA export, signature verification, and artifact
   upload.
5. Download `ios-ipa-…` and `ios-xcarchive-…` artifacts (7-day retention).

Build number (`CFBundleVersion`) equals the workflow run number.

The archive step currently forces `SWIFT_OPTIMIZATION_LEVEL=-Onone` because
Xcode 26.x Release WMO crashes on this project. Signing remains Apple
Distribution.

## Verify an IPA locally

```bash
.github/scripts/verify-ios-release-ipa.sh \
  /path/to/OpenCode\ Mobile.ipa \
  677MTXPXZZ \
  dev.opencode.mobile
```

## Key rotation

1. Create a new ASC Team key in Users and Access → Integrations.
2. Replace files in the credentials folder and update `ios-release` secrets.
3. Revoke the old key in App Store Connect.
4. Confirm the next iOS Release run succeeds.

## Extension points

- **OPE-103** — upload the signed App Store IPA to TestFlight Internal Testing.
- **OPE-102** — build/distribute Ad Hoc IPAs to Firebase App Distribution on
  every `main` merge.
