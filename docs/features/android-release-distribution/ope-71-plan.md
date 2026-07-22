# OPE-71 — Publish To Play Internal And Run Test Lab Robo

Linear: [OPE-71](https://linear.app/opencode-mobile/issue/OPE-71/publish-to-play-internal-and-run-test-lab-robo)
Parent: [OPE-69](https://linear.app/opencode-mobile/issue/OPE-69/android-release-distribution)

## Outcome

Use the signed artifacts established by OPE-70 to publish an Android release to
the Play internal-testing track and run the same release APK through a bounded,
independently triggered Firebase Test Lab Robo matrix.

## Boundary

OPE-71 extends the canonical Android Release workflow; it does not create a
second Play-bound build or version sequence. Test Lab consumes the immutable
APK artifact from a successful Android Release run. Neither workflow runs for a
pull request or gates merge.

This ticket does not add app-side Firebase configuration, a Firebase SDK,
Crashlytics, Analytics, instrumentation tests, physical Test Lab devices, FCM,
or Remote Config.

## Agent implementation

- [x] Upload the signature-verified AAB from Android Release to the Play
      internal-testing track with the existing least-privilege publisher
      credential.
- [x] Keep Play publication in the protected, main-only canonical release
      workflow so it remains the only owner of Play `versionCode` values.
- [x] Add an independent, protected, manually triggered Android Test Lab
      workflow.
- [x] Require Test Lab to consume an APK from a successful Android Release
      `workflow_dispatch` run on `main`.
- [x] Select exactly two current stable `MediumPhone.arm` virtual-device API
      versions from the Test Lab catalog and enforce a ten-minute timeout per
      device.
- [x] Keep both workflows optional and outside pull-request status checks.
- [x] Document triggers, inputs, result inspection, and the human Play
      acceptance step.

## Protected-main verification

- [ ] Merge the workflow changes to `main`.
- [ ] Run Android Release with the next semantic `version_name` and confirm the
      signed AAB reaches Play internal testing.
- [ ] Add the owner's Google account to the internal tester list, follow the
      opt-in URL, and install the Play-delivered build on a physical device.
- [ ] Run Android Test Lab with the successful Android Release run ID and
      confirm both bounded Robo executions complete on virtual devices.
- [ ] Record the release and Test Lab run URLs before completing OPE-71.

## Verification ledger

| Layer | Required evidence |
| --- | --- |
| Workflow contract | Main-only protected jobs, independent manual triggers, no PR trigger |
| Play | AAB upload uses the canonical release artifact and internal track |
| Test Lab source | Selected run is successful Android Release dispatch from `main` |
| Test Lab cost boundary | Exactly two virtual configurations, ten-minute timeout each |
| Device acceptance | Owner opts in and installs the internal release from Play |
| Scope safety | No app-side Firebase SDK or OPE-72 observability work |
