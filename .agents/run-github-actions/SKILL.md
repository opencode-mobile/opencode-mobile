---
name: run-github-actions
description: Trigger, monitor, and troubleshoot opencode-mobile GitHub Actions workflows using the gh CLI. Use when the user asks to run CI, trigger a workflow, check GitHub Actions status, follow up on a failed run, or debug Android/iOS build or check jobs.
---

# Run GitHub Actions

Use the `gh` CLI for all GitHub Actions work in this repo. Do not use the GitHub web UI unless the user asks.

All workflows are **manual only** (`workflow_dispatch`). Nothing runs on push or pull request.

## Workflows

| User intent | Workflow name | File | What it runs |
|---|---|---|---|
| Build Android app | `Android Build` | `.github/workflows/android-build.yml` | `./gradlew :app:assembleDebug` |
| Android lint/style/tests | `Android Checks` | `.github/workflows/android-checks.yml` | `ktlintCheck`, `lintDebug`, `testDebugUnitTest` |
| Build iOS app | `iOS Build` | `.github/workflows/ios-build.yml` | Simulator build, no signing |
| iOS analyze/tests | `iOS Checks` | `.github/workflows/ios-checks.yml` | Build, `xcodebuild analyze`, unit tests |

Notes:
- Android static analysis uses **ktlint**, not detekt.
- iOS has no SwiftLint yet; checks use build + analyzer + unit tests.
- iOS CI needs the shared scheme at `ios/OpenCode Mobile/OpenCode Mobile.xcodeproj/xcshareddata/xcschemes/OpenCode Mobile.xcscheme`.

## Trigger a workflow

1. Confirm `gh auth status` succeeds.
2. Use the current branch unless the user names another ref:

```bash
REF="$(git branch --show-current)"
gh workflow run "Android Checks" --ref "$REF"
```

3. Tell the user which workflow and ref were triggered.

Common triggers:

```bash
gh workflow run "Android Build" --ref main
gh workflow run "Android Checks" --ref main
gh workflow run "iOS Build" --ref main
gh workflow run "iOS Checks" --ref main
```

List workflows if names are unclear:

```bash
gh workflow list
```

## Check status

After triggering, find and watch the run:

```bash
# Latest runs for one workflow
gh run list --workflow="Android Checks" --limit 5

# Watch the newest run until it finishes
gh run watch

# Watch a specific run
gh run watch <run-id>

# Summary only
gh run view <run-id>
```

If the user did not just trigger a run, start from `gh run list --limit 10` or filter by workflow name.

Open in browser only when asked:

```bash
gh run view <run-id> --web
```

## Follow-up troubleshooting

When the user asks about a failed run, stale status, or "what went wrong":

1. Identify the run:
   - Use a run ID the user gave, or
   - `gh run list --workflow="<Workflow Name>" --limit 1`
2. Inspect it:

```bash
gh run view <run-id>
gh run view <run-id> --log-failed
```

3. Find the first failing step and extract the error block. Summarize:
   - workflow + job + step
   - error message
   - likely cause
4. Reproduce locally with the matching command:

**Android Build**
```bash
cd android && ./gradlew :app:assembleDebug --stacktrace
```

**Android Checks**
```bash
cd android && ./gradlew ktlintCheck lintDebug testDebugUnitTest --stacktrace
```

**iOS Build**
```bash
cd "ios/OpenCode Mobile"
xcodebuild -resolvePackageDependencies -project "OpenCode Mobile.xcodeproj" -scheme "OpenCode Mobile"
xcodebuild build \
  -project "OpenCode Mobile.xcodeproj" \
  -scheme "OpenCode Mobile" \
  -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO
```

**iOS Checks**
```bash
cd "ios/OpenCode Mobile"
GITHUB_OUTPUT=/tmp/ios-sim.txt bash ../../.github/scripts/select-ios-simulator.sh
DEST="$(grep '^destination=' /tmp/ios-sim.txt | cut -d= -f2-)"
xcodebuild build -project "OpenCode Mobile.xcodeproj" -scheme "OpenCode Mobile" -configuration Debug -destination "$DEST" CODE_SIGNING_ALLOWED=NO
xcodebuild analyze -project "OpenCode Mobile.xcodeproj" -scheme "OpenCode Mobile" -configuration Debug -destination "$DEST" CODE_SIGNING_ALLOWED=NO
xcodebuild test -project "OpenCode Mobile.xcodeproj" -scheme "OpenCode Mobile" -configuration Debug -destination "$DEST" -only-testing:"OpenCode MobileTests" CODE_SIGNING_ALLOWED=NO
```

5. If local repro fails, fix the code and offer to re-trigger the same workflow.
6. If local repro passes but CI fails, compare environment differences:
   - Android: JDK 17, Ubuntu, clean checkout
   - iOS: `macos-15`, Xcode on the runner, simulator picked by `.github/scripts/select-ios-simulator.sh`

## Common failure patterns

| Symptom | Likely cause | Next step |
|---|---|---|
| `ktlintCheck` failed | Kotlin style violation | Run `./gradlew ktlintCheck` locally; fix or `ktlintFormat` |
| `lintDebug` failed | Android Lint issue | Read lint report from CI log; run `./gradlew lintDebug` locally |
| `testDebugUnitTest` failed | Unit test regression | Run failing test class locally |
| Scheme not found (iOS) | Shared scheme missing from branch | Ensure `xcshareddata/xcschemes/OpenCode Mobile.xcscheme` is committed |
| Simulator destination error (iOS) | Runner has no matching device name | CI uses `select-ios-simulator.sh`; inspect that script output in logs |
| SPM resolve failed | Package checkout/network issue | Re-run; check `Package.resolved` and Factory dependency |
| Signing error (iOS) | CI should disable signing | Confirm workflow passes `CODE_SIGNING_ALLOWED=NO` |

## Response pattern

**After trigger:**
- Workflow name, ref, and command used
- Run ID if available
- Whether you are watching it or how to check later

**After troubleshooting:**
- What failed (step + error)
- Local repro result
- Recommended fix or re-run command
