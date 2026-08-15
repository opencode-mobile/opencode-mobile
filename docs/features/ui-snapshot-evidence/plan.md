# UI Snapshot Evidence

Linear: [OPE-161](https://linear.app/opencode-mobile/issue/OPE-161/on-demand-ui-snapshot-evidence)

## User Goal

On demand, generate UI snapshots that illustrate a change and publish them as
a remote HTML report. Typical ask: isolated component plus the same chrome
inside a Session Chat screen. Tests exist only to produce those images; they
are not pushed.

## In Scope

- An agent skill triggered by phrases such as **create ui snapshots to
  illustrate the changes and publish artifacts**.
- For each named subject (example: assistant message), produce at least:
  - one **isolated** snapshot of that chrome;
  - one **in-session** snapshot of that chrome inside Session Chat (top bar,
    timeline, composer) fed by UI mocks, not a live server.
- Both iOS and Android unless the user names one platform.
- Throwaway snapshot tests that the skill writes, runs in generate-only mode,
  then leaves untracked (gitignored). Generated PNGs and HTML stay out of git.
- Committed scaffolding the throwaway tests need: gitignore rules, a PNG
  writer helper, a Session Chat fixture host, and reusable UI mocks.
- An HTML **sketch** report (light/dark when both were captured) published
  with the `publish-artifact` skill. Paste LAN and Tailscale URLs in the
  reply.
- Default phone width (~390 pt / matching compact Android). Light appearance
  always; dark as a second column when cheap.

## Out Of Scope

- Golden-file verify in Android Checks or iOS Checks.
- Committing throwaway tests, PNGs, or HTML reports.
- XCUITest / instrumented tests as the capture pump.
- Pixel-diff across iOS vs Android (renderers will differ; the report shows
  both, it does not assert equality).
- Replacing Component Gallery, `#Preview` / `@Preview`, or behavioral tests.
- Live-server sessions, connection profiles, or SSE.
- A `workflow_dispatch` CI job (later, if local generate is proven).

## Server APIs Involved

Not applicable — snapshots use in-process UI mocks. No opencode server.

## Expected Platform Logs

Not applicable — no API calls. Snapshot runs may print output paths; those
stay out of canonical docs.

## Domain Objects

- **Subject:** the chrome being illustrated (assistant message, tool card,
  child-session row, …).
- **Frame:** `isolated` | `in-session`.
- **Appearance:** `light` | `dark`.
- **Fixture host:** Session Chat chrome that accepts mock timeline blocks
  without `SessionChatViewModel` bind / `loadMessages`.
- **Mock catalog:** deterministic `SessionChatTimelineBlock` (and the Android
  equivalent) plus session title / composer idle state.

Production `SessionChatView` / `SessionChatScreen` stay out of the snapshot
path; they construct ViewModels and hit the network.

## Screen Model UI States And Actions

Not a product screen. The fixture host is a static composition:

- Isolated: the subject view only, phone width, app background.
- In-session: `OpenCodeTopBar` + timeline (mocked messages, subject visible)
  + idle `OpenCodeSessionComposer`. No taps, no streaming.

No user actions. Skill actions: write tests → generate PNGs → publish.

## Native Navigation Entry Points

Not applicable for app navigation. The entry point is the agent skill
`.agents/skills/publish-ui-snapshots/` (see
[`publish-ui-snapshots-plan.md`](./publish-ui-snapshots-plan.md)).

## Error States

- Subject has no existing view or mock: skill stops and names the missing
  fixture; it does not invent production UI.
- iOS simulator / JDK / Gradle renderer unavailable: skip that platform,
  still publish what was captured, report the gap.
- Publish-artifact fails: keep local HTML path and report it.
- Throwaway files accidentally staged: skill must not `git add` them.

## Permission Behavior

Not applicable.

## Design-System Components

- **Existing:** `OpenCodeTopBar`, `OpenCodeSessionComposer`, timeline row
  renderers (`OpenCodeOutgoingMessage`, `OpenCodeAssistantMessage`,
  `OpenCodeRenderedMarkdown` assistant variant, `OpenCodeToolCallCard`,
  child-session row, Message List host).
  Gallery mocks (`MessageListGalleryMocks`, `RenderedMarkdownGalleryMocks`)
  are a starting catalog, not the in-session fixture.
- **New/extended:** none in this tooling slice. Assistant Message lives in
  the Component Collection (OPE-169).
- **Feature-local:** `SessionChatSnapshotHost` (iOS and Android) that
  renders production child views from mock timeline blocks.
