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

- Build native mobile clients for opencode, not a mobile terminal emulator.
- Use the headless opencode server API as the primary integration surface.
- Treat `/tui/*` endpoints as optional compatibility for a user who already has
  the terminal UI running.
- Assume the opencode server normally runs on the user's development machine for
  the current project.
- Keep the design open to user-managed LAN, VPN, HTTPS, self-hosted, or tunneled
  access, but do not design an app-managed relay or tunnel for v1.

## V1 Scope

The first useful product boundary is session control for a single opencode
server's current project:

- list, create, rename, delete, and fork sessions;
- read session messages;
- send prompts asynchronously;
- stream foreground events and refresh state after reconnect;
- show session status;
- abort active work;
- approve or deny permission requests without setting persistent `remember`
  decisions from mobile;
- inspect session diffs.

## Explicitly Out Of Scope

Do not add these to v1 without revisiting the project docs:

- cross-platform app frameworks;
- app-managed tunnel, relay, or account system;
- push notifications or guaranteed background execution;
- QR pairing or mDNS discovery;
- multi-project switching;
- direct mobile shell execution;
- config, provider auth, auth credential, or MCP administration;
- full file browsing, editing, search, or mobile IDE behavior.

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
  work happens.
- Record intentional platform differences in the same change that introduces
  them.

## API Assumptions

- The opencode OpenAPI 3.1 spec at `/doc` is the source of truth.
- Future Swift and Kotlin clients should be generated or derived from the
  OpenAPI contract where practical.
- Generated API types should be wrapped in a small app domain layer so product
  language remains stable even if server schemas change.
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

- `.cursor/skills` -> `.agents`
- `.claude/skills` -> `.agents`
- `CLAUDE.md` -> `AGENTS.md`

After cloning or when links are missing, run:

```bash
./scripts/sync-agent-links.sh
```

Add or edit skills only under `.agents/<skill-name>/SKILL.md`. Do not create
real directories at `.cursor/skills` or `.claude/skills`.

## Skill Usage

Project skills live under `.agents/` so any agent tooling can discover them.

- Use `.agents/design-system-component/SKILL.md` when adding or changing
  design-system tokens, typography, reusable components, component variants,
  previews, or gallery entries.
- Use `.agents/synced-native-feature/SKILL.md` when planning or implementing a
  feature that should exist in both native apps.
- Use `.agents/run-github-actions/SKILL.md` when triggering GitHub Actions,
  checking workflow status, or troubleshooting failed CI runs.

## Documentation Rules

- Keep `CONTEXT.md` focused on domain terms meaningful to users and product
  collaborators.
- Keep implementation feasibility and API evidence in `docs/feasibility.md`.
- Keep future app architecture in `docs/architecture.md`.
- Keep the canonical design-system planning source under `docs/design-system/`.
- Keep feature progress, decisions, and parity gaps under
  `docs/features/<feature-slug>/`.
- Add ADRs only for decisions that are hard to reverse, surprising without
  context, and the result of a real trade-off.
- Do not couple domain language to generated code names unless the server API has
  made that language part of the user-facing model.
