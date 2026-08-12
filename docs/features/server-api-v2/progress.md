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
  - [ ] [OPE-150](https://linear.app/opencode-mobile/issue/OPE-150/p3-live-stream-and-prompt-on-v2) P3
  - [ ] [OPE-151](https://linear.app/opencode-mobile/issue/OPE-151/p4-permissions-questions-and-composer-catalogs-on-v2) P4
  - [ ] [OPE-152](https://linear.app/opencode-mobile/issue/OPE-152/p5-compact-revert-todos-vcs-and-add-project-on-v2) P5
  - [ ] [OPE-153](https://linear.app/opencode-mobile/issue/OPE-153/p6-capability-gates-cleanup-and-protocol-matrix-tests) P6

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
- [x] P3: V2 `{id,type,data}` envelope parse + `session.next.*` projector
- [x] P3: `prompt_async` → `POST /api/session/{id}/prompt` (+ agent/model switch)
- [x] P3: Unit tests (event projector, prompt routing, v1 SSE/prompt regression)

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
- [x] P3: V2 `{id,type,data}` envelope parse + `session.next.*` projector
- [x] P3: `prompt_async` → `POST /api/session/{id}/prompt` (+ agent/model switch)
- [x] P3: Unit tests (event projector, prompt routing, v1 SSE/prompt regression)

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

- P1: validate against a live v2 server (home list + open session shell).
- P1: confirm archive path or gate the home archive action on v2.
- P2: validate against a live v2 server (open existing session, static history).
- P3: validate against a live v2 server (send prompt, stream tokens, reconnect).
- P4–P6 as in [`plan.md`](plan.md).
