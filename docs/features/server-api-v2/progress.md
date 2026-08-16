# Server API v2 — Progress

## Shared planning

- [x] Research: [`../server-connection/research-opencode-api-v2.md`](../server-connection/research-opencode-api-v2.md)
- [x] Work-split plan: [`plan.md`](plan.md)
- [x] Integration branch `feature/ope-146-server-api-v2` (phase PRs target this,
  not `main`; ship when epic is ready)
- [x] Linear epic [OPE-146](https://linear.app/opencode-mobile/issue/OPE-146/server-api-v2-compatibility)
  - [x] [OPE-147](https://linear.app/opencode-mobile/issue/OPE-147/p0-detect-server-api-protocol) P0
  - [x] [OPE-148](https://linear.app/opencode-mobile/issue/OPE-148/p1-read-only-home-on-v2) P1
  - [x] [OPE-149](https://linear.app/opencode-mobile/issue/OPE-149/p2-chat-history-on-v2) P2
  - [x] [OPE-150](https://linear.app/opencode-mobile/issue/OPE-150/p3-live-stream-and-prompt-on-v2) P3
  - [x] [OPE-151](https://linear.app/opencode-mobile/issue/OPE-151/p4-permissions-questions-and-composer-catalogs-on-v2) P4
  - [x] [OPE-152](https://linear.app/opencode-mobile/issue/OPE-152/p5-compact-revert-todos-vcs-and-add-project-on-v2) P5
  - [x] [OPE-153](https://linear.app/opencode-mobile/issue/OPE-153/p6-capability-gates-cleanup-and-protocol-matrix-tests) P6
  - [ ] [OPE-155](https://linear.app/opencode-mobile/issue/OPE-155/isolate-v1-and-v2-http-remotes-behind-repository-ports)
    post-ship remote extract (probe + Session + Message done; other remotes deferred)

## Design-system dependencies

- None for protocol routing. Capability-gated empty/disabled states reuse
  existing primitives.

## iOS implementation

- [x] P0: `ServerProtocol` / `ServerCapabilities` / `ServerProtocolClassifier`
- [x] P0: Dual health probe in `ProjectsAPIClient.detectProtocol` +
  `ConnectionProbeService`
- [x] P0: Cache protocol on `ServerConnection` / `ActiveConnectionManager`
- [x] P0: Home `probeServer` updates cached protocol
- [x] P0: Diagnostic log area `connection` with `protocol=`
- [x] P0: Unit tests (`ServerProtocolClassifierTests`,
  `ConnectionProbeServiceTests`)
- [x] P1: V2 envelopes + session/project DTOs
- [x] P1: Dual remotes for project/current, session list/get/create, status,
  rename/archive/delete/fork
- [x] P1: Repository routing by cached protocol (Screen Models unchanged)
- [x] P1: Active→busy status mapping
- [x] P1: Unit tests (`SessionProtocolRepositoryTests`)
- [x] P2: V2 message DTOs + `GET /api/session/{id}/message` cursor pages
- [x] P2: Projected Session.Message → domain `Message` (`MessageDomainAdapter`)
- [x] P2: `MessageProtocolRouter` for repository + MessageSync seed
- [x] P2: Unit tests (`MessageProtocolRepositoryTests`, projector cases)
- [x] P3: `GET /api/event` subscribe when cached protocol is v2
- [x] P3: V2 `{id,type,data}` envelope parse + live `session.execution.*` /
  `session.text.*` projector (`session.next.*` still accepted)
- [x] P3: `prompt_async` → `POST /api/session/{id}/prompt` (+ agent/model switch)
- [x] P3: Unit tests (event projector, prompt routing, v1 SSE/prompt regression)
- [x] P4: Permission list/reply dual remotes (`action`/`resources`/`reply`)
- [x] P4: Question list/reply/reject with session-scoped v2 paths
- [x] P4: Abort → `POST /api/session/{id}/interrupt`
- [x] P4: Command/agent list dual remotes; command run on `/api/session/{id}/command`
- [x] P4: Model catalog from `/api/provider` + `/api/model`
- [x] P4: Unit tests (session/command/agent/model protocol routing)
- [x] P5: Compact → `POST /api/session/{id}/compact` (`delivery: steer`)
- [x] P5: Revert → `revert/stage` + `revert/clear` (no auto-commit)
- [x] P5: Todos REST seed skipped on v2 (event-only)
- [x] P5: VCS info/diff dual remotes (`git` → `working`)
- [x] P5: Add-project `GET /api/location` + `GET /api/fs/list`
- [x] P5: Unit tests (session compact/revert/todos/vcs + filesystem protocol routing)
- [x] P6: Hide last-turn review on v2 via `ServerCapabilities`
- [x] P6: Keep working-tree VCS diffs; last-turn navigation and tool-card
  review taps stay off when the flag is false
- [x] P6: `getSessionDiff` returns HTTP 404 on v2 without calling the legacy
  path
- [x] P6: SSE diagnostic log includes `protocol=`
- [x] P6: Protocol matrix unit tests (capabilities, last-turn gate, v2 skip)
- [x] OPE-155: `currentProtocolOrProbe()` on `ActiveConnectionManager`
- [x] OPE-155: `V1SessionRemote` / `V2SessionRemote` behind `SessionRepository`
- [x] OPE-155: `MessageProtocolRouter` lifted to v1/v2 message remotes
- [x] OPE-155: Command, Filesystem, Model, Agent, Project use shared
  protocol-or-probe (remotes deferred)
- [x] OPE-175: Sparse / unwrapped `POST /api/session` create decode
- [x] Live v2: untitled list/get titles map to `New Session`
- [x] Live v2: message list cursor pages omit `order`
- [x] Live v2: `subagent` tool uses the Child Session Row
- [x] Live v2: Stop interrupts timeline child sessions and clears Working
- [x] Live v2: completed compaction snapshots do not keep Compacting…
- [x] Live v2: SSE `session.execution.*` / `session.text.*` project into Working
  and message patches

## Android implementation

- [x] P0: `ServerProtocol` / `ServerCapabilities` / `ServerProtocolClassifier`
- [x] P0: Dual health probe in `ConnectionProbeService` + `HealthApi.detectProtocol`
- [x] P0: Cache protocol on `ServerConnection` / `ActiveConnectionManager`
- [x] P0: Home `probeServer` updates cached protocol
- [x] P0: Diagnostic log area `Connection` with `protocol=`
- [x] P0: Unit tests (`ServerProtocolClassifierTest`,
  `ConnectionProbeServiceTest`, `ActiveConnectionManagerProtocolTest`)
- [x] P1: V2 envelopes + session/project DTOs
- [x] P1: Dual remotes for project/current, session list/get/create, status,
  rename/archive/delete/fork
- [x] P1: Repository routing by cached protocol (Screen Models unchanged)
- [x] P1: Active→busy status mapping
- [x] P1: Unit tests (`SessionProtocolRepositoryTest`)
- [x] P2: V2 message DTOs + `GET /api/session/{id}/message` cursor pages
- [x] P2: Projected Session.Message → domain `Message` (`MessageDomainAdapter`)
- [x] P2: `MessageProtocolRouter` for repository + MessageSync seed
- [x] P2: Unit tests (`MessageProtocolRepositoryTest`, projector cases)
- [x] P3: `GET /api/event` subscribe when cached protocol is v2
- [x] P3: V2 `{id,type,data}` envelope parse + live `session.execution.*` /
  `session.text.*` projector (`session.next.*` still accepted)
- [x] P3: `prompt_async` → `POST /api/session/{id}/prompt` (+ agent/model switch)
- [x] P3: Unit tests (event projector, prompt routing, v1 SSE/prompt regression)
- [x] P4: Permission list/reply dual remotes (`action`/`resources`/`reply`)
- [x] P4: Question list/reply/reject with session-scoped v2 paths
- [x] P4: Abort → `POST /api/session/{id}/interrupt`
- [x] P4: Command/agent list dual remotes; command run on `/api/session/{id}/command`
- [x] P4: Model catalog from `/api/provider` + `/api/model`
- [x] P4: Unit tests (session/command/agent/model protocol routing)
- [x] P5: Compact → `POST /api/session/{id}/compact` (`delivery: steer`)
- [x] P5: Revert → `revert/stage` + `revert/clear` (no auto-commit)
- [x] P5: Todos REST seed skipped on v2 (event-only)
- [x] P5: VCS info/diff dual remotes (`git` → `working`)
- [x] P5: Add-project `GET /api/location` + `GET /api/fs/list`
- [x] P5: Unit tests (session compact/revert/todos/vcs + filesystem protocol routing)
- [x] P6: Hide last-turn review on v2 via `ServerCapabilities`
- [x] P6: Keep working-tree VCS diffs; last-turn navigation and tool-card
  review taps stay off when the flag is false
- [x] P6: `getSessionDiff` returns HTTP 404 on v2 without calling the legacy
  path
- [x] P6: SSE diagnostic log includes `protocol=`
- [x] P6: Protocol matrix unit tests (capabilities, last-turn gate, v2 skip)
- [x] OPE-155: `currentProtocolOrProbe()` on `ActiveConnectionManager`
- [x] OPE-155: `V1SessionRemote` / `V2SessionRemote` behind `SessionRepository`
- [x] OPE-155: `MessageProtocolRouter` lifted to v1/v2 message remotes
- [x] OPE-155: Command, Filesystem, Model, Agent, Project use shared
  protocol-or-probe (remotes deferred)
- [x] OPE-175: Sparse / unwrapped `POST /api/session` create decode
- [x] Live v2: untitled list/get titles map to `New Session`
- [x] Live v2: message list cursor pages omit `order`
- [x] Live v2: `subagent` tool uses the Child Session Row
- [x] Live v2: Stop interrupts timeline child sessions and clears Working
- [x] Live v2: completed compaction snapshots do not keep Compacting…
- [x] Live v2: SSE `session.execution.*` / `session.text.*` project into Working
  and message patches

## Blockers

- Confirm whether user `opencode2` builds mount legacy routes, `/api` only, or
  both (affects P0 detection edge cases). Detection now covers v1-only,
  v2-only (`pid`), dual (prefer v1), and transitional `/api/health` without
  `pid`.
- Upstream: no clear v2 twin for `GET /session/{id}/diff` (P6 degrade).
- Upstream: `POST /api/session/{id}/archive` is desktop-targeted but absent from
  the published OpenAPI index scraped for research; P1 calls it and may 404
  until upstream lands.

## Remaining work

- Live v2 validation for P1–P6 is deferred.
- Optional dead-API cleanup (`share`, `init`, children, sync `sendMessage`,
  `getMessage`, `listProjects`) is deferred; those methods still sit on
  repository interfaces used by many test fakes.
- OPE-155: extract remotes for Command, Filesystem, Model, Agent, and Project
  when those files are next edited.
