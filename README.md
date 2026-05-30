# opencode-mobile

Native iOS and Android clients for remotely controlling [opencode](https://github.com/sst/opencode) sessions through a running opencode server.

This repository holds both app shells, shared product docs, design-system planning, and agent guidance. The apps talk to the headless opencode server API over HTTP and SSE while foregrounded.

## Repository layout

| Path | Purpose |
|---|---|
| `android/` | Kotlin, Jetpack Compose, Material 3 app |
| `ios/OpenCode Mobile/` | SwiftUI app |
| `docs/` | Architecture, feasibility, design system, feature slices |
| `.agents/` | Shared agent skills (source of truth) |
| `scripts/` | Icon sync, OpenAPI fetch/generate, agent symlink setup |
| `AGENTS.md` | Agent and contributor guide |

## Prerequisites

- **Android:** JDK 17, Android SDK (compile SDK 36)
- **iOS:** Xcode with iOS 17.6+ simulator support
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
npm run api:sync        # fetch OpenAPI spec and generate clients
```

## CI

GitHub Actions workflows are manual only (`workflow_dispatch`):

| Workflow | What it does |
|---|---|
| Android Build | Assemble debug APK |
| Android Checks | ktlint, Android Lint, unit tests |
| iOS Build | Simulator build |
| iOS Checks | Build, static analyzer, unit tests |

Trigger from **Actions** on GitHub, or with the GitHub CLI:

```bash
gh workflow run "Android Checks" --ref main
```

After cloning, run `./scripts/sync-agent-links.sh` if agent symlinks are missing.

## Documentation

- [CONTEXT.md](CONTEXT.md) — product terms and domain language
- [AGENTS.md](AGENTS.md) — scope, architecture rules, skill usage
- [docs/architecture.md](docs/architecture.md) — native app architecture
- [docs/design-system/](docs/design-system/) — shared UI system
- [docs/features/](docs/features/) — feature slice progress and decisions

## Agent skills

Skills live in `.agents/` and are symlinked for Cursor and Claude:

```bash
./scripts/sync-agent-links.sh
```

`AGENTS.md` and `CLAUDE.md` share the same content. Edit skills under `.agents/<skill-name>/SKILL.md` only.

## V1 scope (summary)

In scope: session list/control, messages, async prompts, foreground event stream, session status, abort, permission approve/deny, session diffs.

Out of scope for v1: cross-platform frameworks, app-managed tunnels, push notifications, multi-project switching, mobile shell execution, and full IDE-style file editing.

See [AGENTS.md](AGENTS.md) for the full boundary.
