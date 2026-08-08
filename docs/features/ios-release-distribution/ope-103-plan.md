# OPE-103 — Publish To TestFlight Internal Testing

Linear: [OPE-103](https://linear.app/opencode-mobile/issue/OPE-103/publish-to-testflight-internal-testing)
Parent: [OPE-100](https://linear.app/opencode-mobile/issue/OPE-100/ios-release-distribution)

## Outcome

Extend the signed App Store IPA path from OPE-101 so a manual protected
**iOS Release** run uploads the build to App Store Connect and makes it
installable via TestFlight **Internal Testing** only.

**Privacy guarantee (non-negotiable):** after this ticket the app must still be
**not published** and **not available to the public** on the App Store. Closed
testers install only through TestFlight Internal Testing (ASC team users).
Nobody can find or download the app from the public App Store.

Mirrors Android [OPE-71](../android-release-distribution/ope-71-plan.md) Play
internal-track publication (without a Test Lab equivalent).

## Privacy model (Internal vs review vs public)

| Path | Review? | Public App Store? | In OPE-103? |
| --- | --- | --- | --- |
| TestFlight **Internal Testing** | No App Review and no Beta App Review | No | Yes |
| TestFlight **External Testing** | Beta App Review for first build of a version | No (still invite-only) | No |
| App Store release | Full App Review + explicit release | Yes | No |

Uploading a build for Internal Testing does **not** publish the app. Public
availability requires a separate **Submit for Review** and release action,
which this ticket forbids. If you previously heard that “TestFlight needs
review,” that applies to **External** groups — not to Internal Testing, which
is what we use here.

## Boundary

OPE-103 extends the canonical `iOS Release` workflow. It does not create a
second version sequence, a PR-triggered job, or a merge gate. Upload reuses the
existing `ios-release` ASC API Team key — no new GitHub secrets.

This ticket does not:

- submit the app for App Review or release it to the public App Store;
- enable External TestFlight groups (those invite non-ASC users and can trigger
  Beta App Review — still not public, but out of scope);
- distribute via Firebase (OPE-102);
- add Crashlytics / Analytics / app-side Firebase SDK;
- change iOS Checks or simulator Build.

## Decisions locked for this plan

1. **One workflow.** Upload after signature verification inside
   `.github/workflows/ios-release.yml`, same pattern as Android Release → Play.
2. **Upload tool.** Prefer `xcrun altool --upload-app` (or
   `xcrun iTMSTransporter` if altool is unavailable on the runner) authenticated
   with the existing ASC API key files already prepared in the workflow.
3. **No processing poll in CI.** The job succeeds when ASC accepts the upload.
   Build processing and TestFlight availability are a human follow-up, matching
   Play upload + device accept on Android.
4. **Export compliance in the binary.** Set
   `INFOPLIST_KEY_ITSAppUsesNonExemptEncryption = NO` (standard HTTPS/TLS only)
   so Internal Testing is not blocked waiting for a portal questionnaire when
   that answer applies.
5. **Audience.** Internal Testing = App Store Connect users on the team
   (Individual account: owner). No external testers in this slice.
6. **Stay private.** Never click Submit for Review, never release a version, never
   create an External TestFlight group as part of this ticket. Acceptance fails
   if the App Store version is anything other than an unpublished draft state
   (e.g. Prepare for Submission).
7. **Strict App Store marketing version.** Operator `version_name` must be
   `MAJOR.MINOR.PATCH` (integers only). CI `github.run_number` remains the build
   number. ASC rejects prerelease marketing versions (observed on
   `0.1.0-internal.3` → error `-19239`).

## Checkpoint 1 — Human prep (`ready-for-human`)

- [x] Confirm ASC app **OpenCode Mobile — Unofficial** remains
      **Prepare for Submission** / not submitted for App Review (confirmed
      2026-08-08).
- [x] Confirm the owner Apple ID is an App Store Connect user who can join
      Internal Testing (Individual Program owner is enough).
- [x] Install **TestFlight** on the physical verification device.
- [x] Confirm no extra ASC API key is needed (reuse `ios-release-ci` Admin).

Checkpoint complete when Internal Testing is reachable by the owner and the
ticket can move from `ready-for-human` to `ready-for-agent` (or stay
`ready-for-agent` if prep is already true).

## Checkpoint 2 — Agent implementation (`ready-for-agent`)

- [x] Add export-compliance Info.plist key
      (`INFOPLIST_KEY_ITSAppUsesNonExemptEncryption=NO`) to the iOS app target
      build settings for Debug/Release (and document the assumption).
- [x] After IPA signature verification in `ios-release.yml`, upload the IPA to
      App Store Connect with the existing ASC API credentials.
- [x] Fail clearly on upload errors; never print `.p8` material.
- [x] Keep artifact uploads (IPA + xcarchive) as today; upload does not replace
      them.
- [x] Keep the workflow `workflow_dispatch` + `main` + `ios-release` only; no PR
      trigger; concurrency group unchanged.
- [x] Update `operator-runbook.md`: trigger, what “upload accepted” means vs
      “build processed”, how to find the build in TestFlight Internal Testing,
      the first-device install steps, and an explicit **do not Submit for Review /
      do not release / do not add External groups** warning.
- [x] Record the versioning contract in the runbook (marketing version vs build
      number as shown in TestFlight).

Checkpoint complete when the workflow change is merged to `main` and ready for
a protected verification run.

## Checkpoint 3 — Protected `main` verification

- [ ] Run **iOS Release** on `main` with the next semantic `version_name`.
- [ ] Confirm the workflow uploads successfully (ASC accepts the binary).
- [ ] In App Store Connect → TestFlight, wait until the build finishes
      processing and appears under Internal Testing.
- [ ] Confirm the App Store version stays unpublished / not submitted for
      review / not Available on the App Store (still Prepare for Submission or
      equivalent draft). Confirm no External TestFlight group was created.
- [ ] Install the build from TestFlight on a physical device and open the app.
- [ ] Record the Actions run URL and ASC/TestFlight build identity (version +
      build number) in this plan’s evidence section before closing OPE-103.

## Acceptance

OPE-103 is complete only when:

- a protected `main`-only iOS Release run uploads a signed App Store IPA to ASC;
- the build is available on TestFlight Internal Testing and installs for an ASC
  team member;
- the app remains **private**: not submitted for App Review, not released, and
  not publicly available on the App Store;
- no External TestFlight group was enabled for this work;
- the workflow stays independently triggerable and outside PR merge gates;
- versioning (CI build number + operator marketing version) is documented; and
- first end-to-end TestFlight install on a physical device is verified.

## Verification ledger

| Layer | Required evidence |
| --- | --- |
| Workflow contract | Main-only protected job, manual dispatch, upload after verify, no PR trigger |
| ASC upload | Successful upload of the same signature-verified IPA |
| TestFlight | Build visible under Internal Testing after processing |
| Scope safety | No App Review submit, no App Store release, no External group, no Firebase |
| Privacy | ASC App Store status still unpublished draft; app not publicly searchable |
| Device acceptance | Owner installs and opens the TestFlight build on a physical device |
| Secrets | Reuses `ios-release` ASC secrets only |

## Protected-main evidence

_To be filled after the first successful upload + device install._

## Risks

| Risk | Mitigation |
| --- | --- |
| ASC rejects upload for missing compliance | Set `ITSAppUsesNonExemptEncryption=NO` when accurate; otherwise answer once in ASC |
| Build stuck in Processing | Human waits / retries; CI does not poll indefinitely |
| Duplicate build number | `github.run_number` is monotonic per workflow; never reuse a lower build |
| Accidental App Review submit or release | Runbook forbids Submit for Review and Release; acceptance checks draft status |
| Confusing Beta App Review with public publish | Plan uses Internal Testing only (no review); External is out of scope |

## Out of scope reminders

- External TestFlight (Beta App Review path), public listing, App Review, release
- Firebase App Distribution (OPE-102)
- Crashlytics / Analytics
- Changing merge-gate iOS Checks / Build
