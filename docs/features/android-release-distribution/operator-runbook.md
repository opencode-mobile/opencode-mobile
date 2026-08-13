# Android Release — Operator Runbook

This runbook records the non-secret ownership and operating contract for
OPE-70 through OPE-73. Never add a keystore, password, service-account JSON, or
decoded secret to this repository, workflow artifacts, logs, or issue comments.

## Infrastructure inventory

| Resource | Non-secret identifier | Owner / access |
| --- | --- | --- |
| Google Cloud / Firebase project | `opencode-mobile-release` (project number `569439646238`) | Personal Google owner; second administrator optional/deferred for the private phase; solo-owner recovery risk accepted |
| Firebase Android app | `dev.opencode.mobile` (`1:569439646238:android:593114316e11ddb48df40f`) | Same Firebase project; client routing configuration is tracked at `android/app/google-services.json` |
| Google Analytics | Account `OpenCode Mobile`; reporting location Germany | Linked to the Firebase project for OPE-72; all optional account sharing disabled |
| Play developer account | `RawPotato` (developer ID `5676612005420685527`) | Personal owner; legal identity, contact methods, and two-step verification confirmed |
| Play app draft | **OpenCode Mobile — Unofficial** (app ID `4974264711668360153`) | Draft only; independent-client disclaimer saved; not submitted for review |
| Test Lab service account | `opencode-test-lab@opencode-mobile-release.iam.gserviceaccount.com` | Firebase Test Lab Admin, Firebase Analytics Viewer, Firebase Quality Admin, Storage Object Admin, and Editor in the Cloud project |
| Play publisher service account | `opencode-play-publisher@opencode-mobile-release.iam.gserviceaccount.com` | App-scoped Play testing-track release access; no production permission |
| App Distribution service account | `opencode-app-distribution@opencode-mobile-release.iam.gserviceaccount.com` | Firebase App Distribution Admin only; JSON recovery copy is stored in the owner-only credentials folder and the protected GitHub environment |
| GitHub environment | `android-release` | Restricted to the `main` branch |

OPE-70 and OPE-71 added no app-side Firebase SDK. OPE-72 enables Google
Analytics and adds Crashlytics plus Analytics to release builds only. Debug
builds contain no Firebase runtime. The tracked `google-services.json` is
public client routing configuration; it is not a service-account credential
and contains no private key.

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
| `FIREBASE_APP_DISTRIBUTION_SERVICE_ACCOUNT_JSON` | Dedicated App Distribution service-account JSON |

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

Each successful run uploads three artifacts:

- `android-aab-<version-name>-<run-number>` — signed AAB for Play (7 days);
- `android-apk-<version-name>-<run-number>` — signed APK for Test Lab (7 days);
- `android-mapping-<version-name>-<run-number>` — R8 `mapping.txt` (90 days).

The workflow verifies the AAB with `jarsigner`, the APK with `apksigner`, and
confirms the AAB embeds R8 mapping metadata before Play upload. A missing
secret, malformed version, signing failure, signature verification failure, or
missing R8 mapping stops the run. Native debug-symbol metadata is reported when
present; pre-stripped AndroidX `.so` files may omit it without failing the run.

Release builds enable R8 minify and request native debug symbols
(`ndk.debugSymbolLevel = SYMBOL_TABLE`). Mapping rides in the AAB for Play and
is uploaded to Crashlytics by the Firebase Crashlytics Gradle plugin. Resource
shrinking stays off until minify is proven on a Play-internal build. Current
release `.so` files come from AndroidX already stripped, so Play may still show
the native-debug-symbols advisory until unstripped native code is present.

## Upload certificate

- SHA-1: `A2:65:2E:82:3D:44:25:E9:DD:A2:0C:DC:84:A0:32:B7:E7:DD:98:09`
- SHA-256: `9E:FB:99:19:6B:2D:F6:56:FD:FA:1D:88:3D:33:00:7E:0F:E0:DA:28:AB:FF:A6:F8:BC:C3:3D:42:0F:CA:E1:37`

## Play app-signing certificate

Play App Signing was activated by the first uploaded AAB:
`0.1.0-internal.2` / `versionCode` 2. Android Release
[run 29991719142](https://github.com/opencode-mobile/opencode-mobile/actions/runs/29991719142)
published `0.1.0-internal.4` / `versionCode` 4 to the internal-testing track.

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

## Publish to Play internal testing

Android Release publishes its signature-verified AAB to the Play `internal`
track using `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`. This is the only Play-bound
workflow and therefore remains the sole owner of the monotonically increasing
`versionCode` sequence.

### Tester self-invite (Google Group)

Internal testing uses a Google Group so testers can self-join without editing
Play Console per person (cap remains 100):

| Resource | Value |
| --- | --- |
| Google Group | [OpenCode Mobile Testers](https://groups.google.com/g/opencode-mobile-testers) |
| Group email | `opencode-mobile-testers@googlegroups.com` |
| Who can see | Anyone on the web |
| Who can join | Anyone on the web can join |
| Play email list | `OpenCode Mobile Testers` (contains the group email) |
| Play opt-in URL | https://play.google.com/apps/internaltest/4701255816217600017 |

Share both links with testers:

1. Join the Google Group while signed into the Google account they will use on
   the Android device.
2. Open the Play opt-in URL with that same account, Join the test, then install
   **OpenCode Mobile — Unofficial** from Google Play.
3. After a new Android Release, confirm Play Console shows the new version on
   the internal track.

Group membership sync into Play can take minutes to a few hours. Joining the
test via the opt-in link is usually immediate once Play recognizes the account.

The app may remain otherwise incomplete in Play Console during internal
testing. Do not promote the release to closed, open, or production tracks.

### Store listing upload kit

Play Console graphics for the draft listing live in
[`play-listing/`](../../../play-listing/) (icon + feature graphic) and
[`test-screenshots/`](../../../test-screenshots/) (`android_1_*.jpg` phone
shots). The high-res listing icon is the interlocking store mark from
`app-icon/interlock.svg` — regenerate with `npm run app-icon:sync-store`.
On-device launcher icons stay on `app-icon/source.svg`.

The first physical acceptance used `ivanfenenko@gmail.com` and confirmed that
`0.1.0-internal.4` / `versionCode` 4 installed and opened successfully on a
Pixel 9.

## Run Firebase Test Lab Robo

Android Test Lab is an independent manual workflow. It accepts the numeric run
ID of a successful Android Release dispatch from `main`, downloads that run's
signed APK artifact, and rejects other workflow, branch, or conclusion states.

```bash
gh workflow run "Android Test Lab" \
  --ref main \
  -f release_run_id=<android-release-run-id>
```

The workflow authenticates with `GCP_TEST_LAB_SERVICE_ACCOUNT_JSON`, verifies
that `MediumPhone.arm` is virtual, selects its two newest supported stable
Android versions, and runs Robo for at most ten minutes on each configuration.
It does not use physical devices, add instrumentation tests, or gate pull
requests.

The workflow output includes the Firebase result URL. Use it to inspect the
matrix summary, logs, screenshots, and video. A Test Lab failure does not alter
the independently published Play release.

The Firebase-provided default results bucket requires the Test Lab principal to
have the project Editor role. The owner accepted that broader role for the
private phase rather than create a separately billed custom results bucket.
Keep the role limited to the Test Lab service account and revisit a custom
bucket or narrower storage policy before broader distribution.

The first successful protected-main acceptance was Android Test Lab
[run 29993535229](https://github.com/opencode-mobile/opencode-mobile/actions/runs/29993535229).
Its
[Firebase matrix](https://console.firebase.google.com/project/opencode-mobile-release/testlab/histories/bh.e6f5a29142f5b482/matrices/5182549661464327850)
passed `MediumPhone.arm-36-en-portrait` and
`MediumPhone.arm-35-en-portrait`.

## Firebase App Distribution

`Android App Distribution` distributes on every push to protected `main`. It
also accepts a manual `workflow_dispatch` from `main`. Leave `source_ref` empty
to distribute `main`; set it to a branch, tag, or SHA to distribute that commit
instead. Always run the workflow from `main` — `source_ref` only changes the
checked-out code. The `android-release` environment stays restricted to `main`.

It builds one upload-key-signed APK from the selected commit, verifies the
upload certificate, and distributes the APK to the private Firebase group alias
`android-internal`. It does not publish a Play testing-track release, run Test
Lab, or expose credentials to pull requests.

```bash
gh workflow run "Android App Distribution" --ref main
gh workflow run "Android App Distribution" --ref main -f source_ref=feature/ope-146-server-api-v2
```

The workflow uses Firebase CLI `15.24.0` with Application Default Credentials
from `FIREBASE_APP_DISTRIBUTION_SERVICE_ACCOUNT_JSON`. It records the commit
subject and short SHA as release notes (`Merge` on a `main` push, `Dispatch`
on a manual run), then places only the stable Firebase console and
tester-experience links in the workflow summary. The signed APK artifact is
retained for seven days. Firebase App Distribution releases remain available
for 150 days.

App Distribution version codes use `1000000000 + github.run_number`, a
monotonic range reserved for this workflow. The APK uses the upload-key
certificate, which differs from the normal Play app-signing certificate, so the
first migration
from a Play-internal installation requires:

1. Confirm the desired Firebase release is assigned to `android-internal`.
2. Uninstall the Play-internal build from the physical device.
3. Open the tester-experience link while signed in as the private tester.
4. Install and launch the Firebase release.

Later App Distribution builds use the same upload-key certificate and
can update within the Firebase channel. Moving back to Play internal also
requires uninstall/reinstall. Do not create a public invite link. Tester
membership remains a Firebase console/operator action.

The first merged AAB attempt failed because Firebase requires the linked Play
app to be published rather than an unreviewed draft. The direct APK path keeps
the app private and avoids completing Play store-policy declarations or
submitting it for review solely to support merge distribution.

If a distribution run fails, inspect the workflow's build, signature,
authentication, and Firebase upload steps. A failed merge-triggered run does
not revert the merge. Rotate only the dedicated App Distribution key if that
credential is exposed, and replace its protected GitHub secret.

## Verify release observability

Normal releases leave the controlled verification path disabled:

```bash
gh workflow run "Android Release" \
  --ref main \
  -f version_name=<next-semantic-version>
```

After OPE-72 is merged, run exactly one protected verification release:

```bash
gh workflow run "Android Release" \
  --ref main \
  -f version_name=<next-semantic-version> \
  -f verify_crashlytics=true
```

The workflow input compiles a content-free
`OpenCodeMobileCrashlyticsVerification` non-fatal into that release. The app
records it once per installed version code and stores the marker locally.
Install the build from Play internal, launch it once, then relaunch so
Crashlytics can upload the batched report. Confirm the expected version,
version code, device, and exception in the Crashlytics dashboard. A third
launch must not produce another verification event for that version.

The release Analytics contract allows only Firebase's remaining automatic
lifecycle/technical events used as Crashlytics breadcrumbs. The app sends no
custom events, screen views, app-assigned user IDs, or app-defined user
properties. Automatic screen reporting, Advertising ID collection, ad
personalization, Android Privacy Sandbox ad identifiers, and the Play install
referrer permission are disabled or removed. Inspect Analytics DebugView or
event reporting after the verification launch and reject the release if that
boundary is not satisfied.

If the non-fatal does not appear, confirm the installed Play version, relaunch
the app, enable Crashlytics debug logging through ADB, and check for a
successful report upload before changing code. Do not paste raw dashboard
exports, tester identifiers, app content, or credentials into the repository or
Linear.

After an OPE-141 release, also confirm in Play Console that the uploaded AAB no
longer warns about missing deobfuscation/mapping, and that the Crashlytics
verification stack is symbolicated (class and method names readable). A remaining
native-debug-symbols advisory is expected while only pre-stripped AndroidX
`.so` files are present.

OPE-72 protected-main acceptance used Android Release
[run 30009973764](https://github.com/opencode-mobile/opencode-mobile/actions/runs/30009973764),
which published `0.1.0-internal.5` / `versionCode` 5. Firebase received the
content-free `OpenCodeMobileCrashlyticsVerification` non-fatal, and the
Analytics event report showed no custom events or screen views. That release
also exposed an API-33-only markdown startup call.

OPE-74 fixed the compatibility defect in pull request
[70](https://github.com/opencode-mobile/opencode-mobile/pull/70). Replacement
Android Release
[run 30011576034](https://github.com/opencode-mobile/opencode-mobile/actions/runs/30011576034)
published `0.1.0-internal.6` / `versionCode` 6. Repeated physical-device launches
on the affected Pixel 9 succeeded. Firebase recognized version 6 as the latest
active release while the prior startup-crash issue remained limited to version
5.
