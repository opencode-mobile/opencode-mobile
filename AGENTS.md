# opencode-mobile Agent Guide

`opencode-mobile` contains native iOS and Android apps that remotely control
opencode sessions through an opencode server.

The implementations live under `android/` (Kotlin, Jetpack Compose, Material 3)
and `ios/OpenCode Mobile/` (SwiftUI). Both apps, API generation, CI, and private
release distribution are active. Product vocabulary lives in `CONTEXT.md`.

## Product Direction

- Maintain native mobile clients for opencode.
- Use the headless opencode server API as the primary integration surface.
  `/tui/*` endpoints are optional compatibility for a user who already has the
  terminal UI running.
- The opencode server normally runs on the user's development machine. Keep
  the design open to user-managed LAN, VPN, HTTPS, self-hosted, or tunneled
  access.

## Native App Architecture

- SwiftUI on iOS; Kotlin with Jetpack Compose and Material 3 on Android. Keep
  the architecture conceptually aligned across platforms, implemented
  separately with each platform's native tools.
- Generated API clients stay behind a small domain adapter. Feature code uses
  mobile product terms, not raw generated server schemas.
- Feature UI state goes through a Screen Model: one immutable UI state plus
  explicit user actions. SwiftUI Observation and MainActor async tasks on iOS;
  ViewModel, StateFlow, and coroutines on Android.
- Connection Profile secrets live in iOS Keychain and Android Keystore-backed
  storage. SwiftData and Room are the default structured persistence choices.

## Xcode And Simulator Tooling

Which Xcode tooling is available depends on the harness (Xcode's MCP bridge,
`XcodeBuildMCP` configured in `.xcodebuildmcp/config.yaml`, a harness
simulator tool, or plain `xcodebuild`). Use what the session provides; do not
assume another exists. Xcode's own bridge operates on the workspace open in
Xcode, so make sure the intended project is open before using it. Build, test,
and source-edit actions through these tools are project mutations: keep them
within the requested scope and report results.

Measured device geometry for unusual form factors (iPhone Duo poses, safe
areas, status column, fold, system bars, simulator pose control) lives in
`docs/devices/`; use it for layouts and prototypes instead of re-measuring.

## Design System And Feature Workflow

- One conceptual Design System for both platforms: shared semantic meaning,
  platform-specific SwiftUI and Material 3 mappings.
- Public design-system component APIs are variant-first and state-aware. Do
  not expose raw hex colors, one-off typography, arbitrary gradients, or
  ad-hoc radius overrides as public customization.
- Feature UI follows the component-first order: use an existing Component
  Collection primitive; otherwise add or extend a reusable component through
  the design-system workflow before composing the screen; use feature-local
  custom UI only when neither fits.
- Feature work proceeds as a synchronized Feature Slice across both apps.
- On iOS, do not place design-system list components (OpenCodeListItem, etc.)
  inside SwiftUI `List`. They carry their own padding, background, and tap
  handling. Use `ScrollView` + `VStack` with manual section headers.

## API Assumptions

- Support **both** server protocols on every Connection Profile: classic
  unprefixed **v1** and `/api/*` **v2**. Detect `v1` | `v2` on connect. Do not
  remove, skip, or "v2-only" a repository, remote, event path, or test matrix
  while adding the other protocol.
- Route protocol differences behind remotes and domain adapters. Feature UI and
  Screen Models stay on product types, not wire schemas or a v1/v2 UI fork.
- Live Session Chat **UX** tracks official TUI v2 (`GET /api/event` on opencode
  `origin/beta`). That is the live *shape* spec, not permission to drop v1.
- **Both** protocols must deliver relevant live events over SSE (session
  lifecycle, message/part updates, status, permissions, questions, not just
  heartbeats) so home and Session Chat update without waiting on REST poll.
  REST is the reconnect / history authority, not a substitute for missing SSE.
- v1 event paths: `GET /global/event` **and** instance `GET /event`
  (directory-scoped). Official desktop uses `/global/event`; instance `/event`
  is the bus that carries `session.created`, `message.*`, and the rest. v2:
  `GET /api/event`.
- The opencode OpenAPI 3.1 spec at `/doc` (v2: `/openapi.json`) is the contract.
- Check the server health endpoint before treating a Connection Profile as
  usable.
- Use server-sent events only while the app is foregrounded; refresh on
  reconnect rather than promising background synchronization.

## Security Assumptions

- Require `OPENCODE_SERVER_PASSWORD` for supported use. Default Basic Auth
  username is `opencode` unless `OPENCODE_SERVER_USERNAME` is configured.
- Store credentials in iOS Keychain and Android Keystore.
- Do not document plain public HTTP exposure as safe. Users are responsible for
  trusted transport such as LAN, VPN, HTTPS, or a tunnel.

## Issues, Branches, Documentation

- Issues are GitHub issues on `opencode-mobile/opencode-mobile`; use `gh`. The
  issue owns scope, acceptance criteria, plans, status, remaining work, and
  temporary iOS/Android gaps. Code and tests own implemented behavior; git and
  pull requests own shipped history.
- Do not create or update repository documentation during implementation
  unless the user explicitly asks for it. `docs/` holds stable reference
  material, not feature ledgers, progress logs, ticket plans, research dumps,
  generated reports, or prototypes. `CONTEXT.md` stays limited to product
  vocabulary.
- Ticket-backed branches: `feature/<number>-<short-slug>`, or
  `feature/ope-<number>-<short-slug>` when the issue title starts with
  `OPE-<number>:`. Use `fix/` instead of `feature/` for bug tickets. Slug is
  lowercase ASCII with single hyphens; whole name at most 80 characters.
- A pull request that changes what users see carries screenshot evidence for
  each affected platform, posted on GitHub: an `evidence/<issue>` branch
  referenced from a pull request or issue comment, never a link to the
  artifact server or another private host. Capture it while verifying, not
  afterwards. A pull request with nothing visible says "No UI change" under
  Checks.

## Pull Request Evidence

- Show a change working when the diff and tests alone do not let a reviewer
  judge it. Skip evidence for refactors, test-only, CI, and tooling changes,
  and say so under Checks.
- Match the evidence to the change:
  - How a screen or component looks: throwaway snapshot tests through
    `publish-ui-snapshots`. No server needed.
  - Behavior, flows, or anything that touches the server API or live events:
    the real app against a real server through `scenario-harness`, shown as
    screenshots, a short GIF, or a request/event trace.
- Cover both platforms. Name what was not checked under Risks / Follow-ups.
- Save each verification screenshot as `ios-NN-<step>.png` /
  `android-NN-<step>.png` as you go, publish the set with
  `scripts/publish-evidence.sh <issue> <dir>`, and post the table it prints as
  a pull request comment. Never commit evidence to a feature branch. If the
  push fails, say so in the pull request instead.
- Capture only harness or mock data: no real passwords or personal
  transcripts.

## Skills

Shared skills live under `.agents/skills/<name>/SKILL.md` and are the only
place to add or edit one. `.cursor/skills`, `.claude/skills`, and `CLAUDE.md`
are symlinks; restore them with `./scripts/sync-agent-links.sh` if missing.

## Cursor Cloud (Linux VM)

- `.cursor/Dockerfile` is the source of truth for the dev image (JDK 17, Node
  22, Android SDK at `/opt/android-sdk`). Change the toolchain by editing it,
  not a snapshot. The install step runs `npm install` and regenerates
  `android/local.properties`.
- Only the Android app and `scripts/` Node tooling run on Linux. iOS needs
  macOS + Xcode; use the macOS GitHub Actions workflows.
- No KVM, so no emulator, `./run-android.sh`, or `connectedDebugAndroidTest`.
  Validate with `./gradlew :app:assembleDebug` plus the JVM unit-test suites.
- `ktlintCheck` and `lintDebug` currently report violations; Android Checks
  soft-fails those steps and hard-fails on unit tests. That is not an
  environment problem. A few coroutine-timing tests (`SessionChatViewModelTest`,
  `ConnectionProfileStoreTest`) are flaky; re-run or run the class alone.
- `shared/markdown-core` is a separate Gradle module rooted at
  `/workspace/shared/markdown-core`, not under `android/`.
