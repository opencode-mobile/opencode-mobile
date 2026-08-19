# opencode-mobile Agent Guide

`opencode-mobile` is the planning home for two future native mobile apps, one
for iOS and one for Android, that remotely control opencode sessions through an
opencode server.

This repository documents product scope, feasibility, and native app
architecture. App shells exist: `android/` (Kotlin, Jetpack Compose, Material 3)
and `ios/OpenCode Mobile/` (SwiftUI, SwiftData template). Running API client
generators, broad feature UI, and release infrastructure are not started unless
the docs say otherwise.

## Product Direction

- Build native mobile clients for opencode.
- Use the headless opencode server API as the primary integration surface.
- Treat `/tui/*` endpoints as optional compatibility for a user who already has
  the terminal UI running.
- Assume the opencode server normally runs on the user's development machine for
  the current project.
- Keep the design open to user-managed LAN, VPN, HTTPS, self-hosted, or tunneled
  access.

## Native App Architecture

- Future apps should be native SwiftUI on iOS and Kotlin with Jetpack Compose
  plus Material 3 on Android.
- Keep architecture conceptually aligned across platforms while implementing it
  separately with each platform's native tools.
- Generated API clients should remain behind a small domain adapter. Feature
  code should use mobile product terms instead of raw generated server schemas.
- Feature UI state should be exposed through a Screen Model: one immutable UI
  state plus explicit user actions or events. Use SwiftUI Observation and
  MainActor async tasks on iOS where appropriate; use Android ViewModel,
  StateFlow, and coroutines on Android.
- Store Connection Profile secrets in iOS Keychain and Android
  Keystore-backed storage. Use SwiftData and Room as the default structured
  persistence choices when local app-owned data is needed.

## Xcode MCP

- Xcode's MCP bridge is configured for Codex as the `xcode` server. Use its
  tools when iOS work benefits from Xcode context or capabilities: project
  navigation, source reads and edits, compiler diagnostics, builds, tests,
  SwiftUI preview rendering, build logs, and Apple documentation search.
- Before using Xcode MCP tools, ensure the intended iOS project is open in
  Xcode. The bridge operates on Xcode's active workspace rather than treating
  the filesystem as the project model.
- Prefer Xcode MCP for Xcode-owned actions such as building, testing, project
  navigator changes, and preview rendering. Use normal repository tools for
  cross-platform and plain filesystem work.
- Treat build, test, and source-edit MCP actions as project mutations: use them
  only when they are within the user's requested scope, and report relevant
  results.

## Design System And Feature Workflow

- Build the Component Collection before feature screens depend on reusable UI.
- Keep one conceptual Design System for both platforms: shared semantic meaning,
  platform-specific SwiftUI and Material 3 mappings.
- Public design-system component APIs should be variant-first and state-aware.
  Do not expose raw hex colors, one-off typography, arbitrary gradients, or
  ad-hoc radius overrides as public customization.
- Feature UI must follow the component-first fallback order: use an existing
  Component Collection primitive when one fits; create or extend a reusable
  component through the design-system workflow when one is missing; use
  feature-local custom UI only after those options are unsuitable, and document
  why.
- When a feature needs a reusable primitive, component, or semantic variant,
  add it through the design-system component workflow before composing the
  feature screen.
- Feature work should proceed as a synchronized Feature Slice across both apps.
  Document progress and Parity Gaps under `docs/features/<feature-slug>/` as
  work happens. Live contracts only — see `docs/features/README.md`.
- Record intentional platform differences in the same change that introduces
  them.
- On iOS, do not place design-system list components (OpenCodeListItem, etc.)
  inside SwiftUI `List`. The components have their own padding, background, and
  tap handling. Use `ScrollView` + `VStack` with manual section headers instead
  to avoid conflicting row insets, separators, and double padding.

## API Assumptions

- Support **both** server protocols on every Connection Profile: classic
  unprefixed **v1** and `/api/*` **v2**. Detect `v1` | `v2` on connect. Do not
  remove, skip, or “v2-only” a repository, remote, event path, or test matrix
  while adding the other protocol.
- Route protocol differences behind remotes and domain adapters. Feature UI and
  Screen Models stay on product types, not wire schemas or a v1/v2 UI fork.
- Live Session Chat **UX** tracks official TUI v2 (`GET /api/event` on opencode
  `origin/beta`). That is the live *shape* spec, not permission to drop v1.
- **Both** protocols must deliver relevant live events over SSE (session
  lifecycle, message/part updates, status, permissions, questions — not just
  heartbeats) so home and Session Chat update without waiting on REST poll.
  REST is the reconnect / history authority, not a substitute for missing SSE.
- v1 event paths: `GET /global/event` **and** instance `GET /event`
  (directory-scoped). Official desktop uses `/global/event`; instance `/event`
  is the bus that carries `session.created`, `message.*`, and the rest. Do not
  treat heartbeat-only as acceptable. v2: `GET /api/event`.
- The opencode OpenAPI 3.1 spec at `/doc` (v2: `/openapi.json`) is the contract.
- Generated API types stay behind a small domain adapter.
- Use the server health endpoint before treating a Connection Profile as usable.
- Use server-sent events only while the app is foregrounded; refresh on reconnect
  rather than promising reliable background synchronization.

## Security Assumptions

- Require `OPENCODE_SERVER_PASSWORD` for supported use.
- Use the default Basic Auth username `opencode` unless the user configures
  `OPENCODE_SERVER_USERNAME`.
- Future apps should store credentials in iOS Keychain and Android Keystore.
- Do not document plain public HTTP exposure as safe. Users are responsible for
  trusted transport such as LAN, VPN, HTTPS, or a tunnel.

## Agent Symlinks

`.agents/` and `AGENTS.md` are the source of truth for shared agent guidance.

Tooling-specific paths are symlinks:

- `.cursor/skills` -> `.agents/skills`
- `.claude/skills` -> `.agents/skills`
- `CLAUDE.md` -> `AGENTS.md`

After cloning or when links are missing, run:

```bash
./scripts/sync-agent-links.sh
```

Add or edit skills only under `.agents/skills/<skill-name>/SKILL.md`. Do not
create real directories at `.cursor/skills` or `.claude/skills`.

## Skill Usage

Project skills live under `.agents/skills/` so any agent tooling can discover
them.

- Use `.agents/skills/design-system-component/SKILL.md` when adding or changing
  design-system tokens, typography, reusable components, component variants,
  previews, or gallery entries.
- Use `.agents/skills/synced-native-feature/SKILL.md` when planning or
  implementing a feature that should exist in both native apps.
  Feature docs follow `docs/features/README.md`.
- Use `.agents/skills/run-github-actions/SKILL.md` when triggering GitHub
  Actions, checking workflow status, or troubleshooting failed CI runs.
- Use `.agents/skills/pull-app-logs/SKILL.md` when reading Android logcat or
  iOS simulator/device logs for OpenCode Mobile runtime debugging.
- Use `.agents/skills/linear-project-tracking/SKILL.md` when reading or
  updating Linear. Tickets live in Linear; do not copy them into `docs/`.
- Use `.agents/skills/feature/SKILL.md` when linking a Linear ticket, branch,
  feature docs directory, merge checklist, and pull request for a feature task.
- Use `.agents/skills/publish-prototype/SKILL.md` when building a throwaway
  browser prototype and publishing it to the artifact server for remote review.
- Use `.agents/skills/publish-ui-snapshots/SKILL.md` when the user asks to
  create ui snapshots to illustrate the changes and publish artifacts.

## Branch naming

Ticket-backed work uses a type prefix, never a username or agent name:

```text
feature/ope-<number>-<short-slug>
```

- Prefer `feature/` for product feature work tied to an `OPE-<number>` issue.
- Use `fix/` for bug-fix tickets when the issue is clearly a fix rather than
  a new capability.
- Keep the slug lowercase ASCII with single hyphens; omit leading/trailing
  hyphens; keep the full branch name at 80 characters or fewer.
- Ignore Linear's default `{username}/…` branch suggestion. Do not create
  branches like `ivanfenenko/ope-48-…` or `codex/ope-48-…`.

Detailed resolution and publication rules live in
`.agents/skills/feature/reference.md`.

## Agent skills

### Issue tracker

Issues are tracked in Linear; use `gh` for GitHub repository and pull-request
operations. See `docs/agents/issue-tracker.md`.

### Triage labels

Triage uses the five default canonical labels. See
`docs/agents/triage-labels.md`.

### Domain docs

Domain documentation uses the single-context layout. See
`docs/agents/domain.md`.

## Documentation Rules

- Keep `CONTEXT.md` focused on domain terms meaningful to users and product
  collaborators.
- Keep implementation feasibility and API evidence in `docs/feasibility.md`.
- Keep future app architecture in `docs/architecture.md`.
- Keep the canonical design-system planning source under `docs/design-system/`.
- Keep current feature state in `docs/features/<feature-slug>/plan.md`.
  Add `decisions.md` / `parity-gaps.md` only when they still constrain work.
  Do not add progress logs, ticket plans, or research dumps.
- Commit durable documentation artifacts. Keep scratch notes, debug dumps,
  temporary investigations, and reproducible generated noise out of git.
- Do not couple domain language to generated code names unless the server API has
  made that language part of the user-facing model.

## Cursor Cloud specific instructions

Durable, non-obvious facts for working in the Cursor Cloud Linux VM. Standard
build/lint/test commands live in `README.md` and `android/README.md`; this
section only adds caveats.

- **Environment is code.** `.cursor/environment.json` builds `.cursor/Dockerfile`,
  which is the source of truth for the dev image (saving a snapshot is a no-op).
  Change the toolchain by editing the Dockerfile, not by mutating a snapshot. The
  image preinstalls JDK 17, Node.js 22, and the Android SDK
  (`platform-tools`, `platforms;android-36`, `build-tools;36.0.0`) at
  `/opt/android-sdk`, with `JAVA_HOME` / `ANDROID_HOME` / `PATH` baked in. The
  `install` step runs `npm install` and regenerates gitignored
  `android/local.properties` from `$ANDROID_HOME`.
- **Platform reachability.** The VM is Linux, so only the Android app and the
  Node tooling under `scripts/` are runnable here. The iOS app cannot be built or
  tested on Linux (it needs macOS + Xcode and runs a Gradle Apple-framework phase
  against `shared/markdown-core`); use the macOS GitHub Actions workflows for iOS.
- **No Android emulator.** The VM has no `/dev/kvm` / nested virtualization, so a
  hardware-accelerated emulator (and therefore `./run-android.sh` and
  `connectedDebugAndroidTest`) cannot run here. Validate the app via
  `./gradlew :app:assembleDebug` plus the JVM unit-test suites; the build emits
  an installable `app/build/outputs/apk/debug/app-debug.apk`.
- **Toolchain detection.** The project pins `jvmToolchain(17)`; Gradle
  auto-detects the JDK 17 toolchain, so builds work even when a shell defaults to
  another JDK.
- **Pre-existing check failures (not environment issues).** On the current
  committed tree, `./gradlew ktlintCheck` and `./gradlew lintDebug` report
  violations (ktlint style nits plus `NewApi` lint errors with no lint baseline).
  Android Checks soft-fails those steps and hard-fails on unit tests until a
  follow-up makes lint green. Do not treat the soft-fail as a broken environment.
- **Flaky unit tests.** A few coroutine-timing tests are non-deterministic across
  runs (e.g. some `SessionChatViewModelTest` and `ConnectionProfileStoreTest`
  cases) because background work runs off the test scheduler. Re-run the task or
  run the class in isolation. `shared/markdown-core` and the core `domain.*` /
  `MainScreenViewModelTest` suites are deterministic and pass.
- **Module layout.** `shared/markdown-core` is a separate Gradle module rooted at
  `/workspace/shared/markdown-core` (not under `android/`); its build outputs and
  test reports live there.
