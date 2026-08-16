# opencode-mobile

Native iOS and Android clients for remotely controlling [opencode](https://github.com/sst/opencode) sessions through a running opencode server.

This repository holds both app shells, shared product docs, design-system planning, and agent guidance. The apps talk to the headless opencode server API over HTTP and SSE while foregrounded.

## Repository layout

| Path | Purpose |
|---|---|
| `android/` | Kotlin, Jetpack Compose, Material 3 app |
| `ios/OpenCode Mobile/` | SwiftUI app |
| `docs/` | Architecture, feasibility, design system, feature slices |
| `.agents/` | Shared agent guidance and canonical skills under `.agents/skills/` |
| `scripts/` | Icon sync, OpenAPI fetch/generate, agent symlink setup |
| `AGENTS.md` | Agent and contributor guide |

## Prerequisites

- **Android:** JDK 17, Android SDK (compile SDK 36)
- **iOS:** Xcode with iOS 17.5+ simulator support
- **Dev tooling:** Node.js for repo scripts (`npm install` at repo root)

## Local development

### Android

```bash
cd android
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew ktlintCheck
```

See [android/README.md](android/README.md) for code style details.

### iOS

Open `ios/OpenCode Mobile/OpenCode Mobile.xcodeproj` in Xcode, or build from the CLI:

```bash
cd "ios/OpenCode Mobile"
xcodebuild build \
  -project "OpenCode Mobile.xcodeproj" \
  -scheme "OpenCode Mobile" \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO
```

### Repo scripts

```bash
npm install
npm run icons:sync      # regenerate shared Material icons
npm run themes:sync     # sync OC-2 desktop theme presets into app bundles
npm run themes:check    # verify shared/iOS/Android preset copies match
npm run api:sync        # fetch OpenAPI spec and generate clients
```

## CI

Checks run for every **ready-for-review** pull request (drafts are skipped).
Android runs on the private Fedora box (`self-hosted, Linux, X64, android`);
iOS runs on the private Mac mini (`self-hosted, macOS, mac-mini`). Android
Build and iOS Build stay manual.

| Workflow | Trigger | Runner | What it does |
|---|---|---|---|
| Android Checks | PR (non-draft) + dispatch | Fedora box (`self-hosted, Linux, X64, android`) | ktlint/lint (soft-fail) + unit tests |
| iOS Checks | PR (non-draft) + dispatch | Mac mini (`self-hosted, macOS, mac-mini`) | Build, analyzer, unit tests |
| Android Build | `workflow_dispatch` | Fedora box (`self-hosted, Linux, X64, android`) | Assemble debug APK |
| Android Release | `workflow_dispatch` on `main` | Fedora box (`self-hosted, Linux, X64, android`) | Build and verify signed AAB + APK, then publish to Play internal |
| Android App Distribution | `push` to `main`, or `workflow_dispatch` | Fedora box (`self-hosted, Linux, X64, android`) | Build and verify a signed APK, then distribute it to the private Firebase tester group |
| Android Test Lab | `workflow_dispatch` on `main` | Fedora box (`self-hosted, Linux, X64, android`) | Run bounded Robo tests against a signed release APK |
| iOS Build | `workflow_dispatch` | Mac mini (`self-hosted, macOS, mac-mini`) | Simulator build |
| iOS App Distribution | `push` to `main`, or `workflow_dispatch` | Mac mini (`self-hosted, macOS, mac-mini`) | Build and verify a signed Ad Hoc IPA, then distribute it to the private Firebase tester group |

To block merges, require status checks `android-checks` and `ios-checks` on
`main` (repository ruleset or branch protection, strict up-to-date). See
[`docs/features/ci-pr-gating/`](docs/features/ci-pr-gating/).

Manual runs:

```bash
gh workflow run "Android Checks" --ref main
gh workflow run "Android Release" --ref main -f version_name=0.1.0
gh workflow run "Android Test Lab" --ref main -f release_run_id=<android-release-run-id>
gh workflow run "Android App Distribution" --ref main
gh workflow run "Android App Distribution" --ref feature/ope-146-server-api-v2
gh workflow run "iOS App Distribution" --ref main
gh workflow run "iOS App Distribution" --ref feature/ope-146-server-api-v2
```

Android Release, Android App Distribution, and Android Test Lab use the
protected `android-release` environment. iOS App Distribution uses the
protected `ios-release` environment. See the
[Android release operator runbook](docs/features/android-release-distribution/operator-runbook.md)
and
[iOS release operator runbook](docs/features/ios-release-distribution/operator-runbook.md)
for secrets, artifacts, and recovery instructions. iOS runner setup, reboot
recovery, and fallback are in
[docs/mac-mini-runner.md](docs/mac-mini-runner.md).

After cloning, run `./scripts/sync-agent-links.sh` if agent symlinks are missing.

## Documentation

- [CONTEXT.md](CONTEXT.md) — product terms and domain language
- [AGENTS.md](AGENTS.md) — scope, architecture rules, skill usage
- [docs/architecture.md](docs/architecture.md) — native app architecture
- [docs/design-system/](docs/design-system/) — shared UI system
- [docs/features/](docs/features/) — feature slice progress and decisions

## Agent skills

Skills live in `.agents/skills/` and are symlinked for Cursor and Claude:

```bash
./scripts/sync-agent-links.sh
```

`AGENTS.md` and `CLAUDE.md` share the same content. Edit skills under `.agents/skills/<skill-name>/SKILL.md` only.

## V1 scope (summary)

In scope: session list/control, messages, async prompts, foreground event stream, session status, abort, permission approve/deny, session diffs.

Out of scope for v1: cross-platform frameworks, app-managed tunnels, push notifications, multi-project switching, mobile shell execution, and full IDE-style file editing.

See [AGENTS.md](AGENTS.md) for the full boundary.
