# Server API v2 — Decisions

## Stack phase PRs on an integration branch (2026-08-12)

**Choice:** Land all Server API v2 phases on
`feature/ope-146-server-api-v2`. Phase PRs (OPE-147…OPE-153) target that branch,
not `main`. Merge the integration branch to `main` once when the epic is ready.

**Why:** Keeps incomplete dual-stack work off `main` while still allowing
reviewable vertical slices. One ship decision for the whole migration.

**Alternative rejected:** Merge each phase to `main` as it finishes — would
expose half-routed remotes and capability gaps to production tracks before the
stack is coherent.

## Dual-stack behind repositories (2026-08-12)

**Choice:** Detect `v1` \| `v2` on connect; route under remotes/adapters;
keep Screen Models on existing domain types. Do not fork UI into protocol modes.

**Why:** Architecture already separates UI → repositories → remotes. Desktop
uses the same pattern (`detectServerProtocol` + compatible API). MessageSync/SSE
is the hard seam and belongs below UI.

**Alternative rejected:** Regenerate OpenAPI clients and feature-flag screens
per protocol — larger UI churn, worse parity.

## Capability flags for missing v2 twins (2026-08-12)

**Choice:** Expose `ServerCapabilities` (e.g. `supportsSessionMessageDiff`) and
hide/disable product actions on v2 when upstream has no equivalent.

**Why:** Review Changes “Last turn” is product-used but blocked on v2; better to
degrade than block all v2 support.

## Connection probe does not default to v2 (2026-08-12)

**Choice:** Mirror desktop classification for successful health payloads, but if
neither `/global/health` nor `/api/health` is usable, fail the connection probe
instead of defaulting to `v2`.

**Why:** Desktop `detectServerProtocol` defaults to `v2` when probes miss; that
is unsafe for Test Connection / home reachability (would mark dead servers as
reachable). Classification matrix still matches desktop for v1-only, v2-only
(`pid`), dual (prefer v1), and transitional `/api/health` without `pid`.

## V2 fork body uses `messageID` (2026-08-12)

**Choice:** P1 fork on v2 posts `{ messageID }` per the published OpenAPI
fixture and desktop dialog, not a nested `boundary` object.

**Why:** Research noted a possible `boundary: { type, messageID }` shape, but
the OpenAPI fixture and current desktop fork call use flat `messageID`. Prefer
the published contract until upstream documents otherwise.

## V2 chat history projects into domain Message (2026-08-12)

**Choice:** Map v2 `Session.Message.*` into today’s domain `Message` /
`MessagePart` in `MessageDomainAdapter` (not a Screen Model fork). Skip
`agent-switched` / `model-switched` / `system` as timeline rows (apply agent
and model onto later user/assistant messages). Expand `shell` into a user
command plus an assistant `bash` tool message. Keep `compaction` as its own
assistant message with `mode = compaction`.

**Why:** MessageSync, the display pipeline, and Session Chat already consume
domain `Message`. Desktop uses a similar projection into v1-shaped
messages/parts. Static history can ship without waiting for P3 event
normalization.

**Alternative rejected:** Keep v2 wire types in the store — would fork
MessageSync and timeline rendering per protocol.

## V2 live events project into OpencodeEvent (2026-08-12)

**Choice:** Parse `/api/event` native `{id,type,data}` envelopes in
`EventDomainAdapter` and project live v2 event names (and the older
`session.next.*` aliases, plus `permission.v2.*` / `question.v2.*`) into
today’s `OpencodeEvent` types before MessageSync and LiveStores see them.
Text streaming uses synthetic `message.part.delta`; turn completion
(`session.execution.succeeded|failed|interrupted`, or legacy
`session.next.step.ended`) becomes `session.idle` so reconnect-style full
reload remains the authority catch-up. Live `session.step.ended` is not
idle — a turn can include multiple steps.

**Why:** MessageSync already applies `message.*` / `session.idle`. Live v2
(`0.0.0-beta-17498`) OpenAPI `V2Event` uses `session.execution.*` /
`session.text.*` / `session.compaction.*`, not `session.next.*`. Home
Working/Idle only reacts to projected `session.status` and `session.idle`.

**Alternative rejected:** Fork MessageSync per protocol.

## V2 prompt send keeps PromptSendOptions (2026-08-12)

**Choice:** Composer still builds `PromptSendOptions`. On v2 the router
optionally `POST .../agent` and `POST .../model`, then
`POST /api/session/{id}/prompt` with top-level `{text,files,agents,delivery}`
and `delivery: steer`. Agent mention ranges go on `agents[].mention`.

**Why:** Live v2 (`0.0.0-beta-17498`) and published OpenAPI require `text` at
the payload root (`Missing key at ["text"]` if nested under `prompt`).
Per-prompt agent/model stay on dedicated session routes. Attachments map onto
`files` (`uri` ← data URL). v1 `prompt_async` + `parts[]` stays unchanged.

## V2 session interactions map onto existing domain types (2026-08-12)

**Choice:** P4 remotes dual-map behind existing repositories. Screen Models
keep today's permission, question, command, agent, and model types.

- Permission: `action` → `permission`, `resources` → `patterns`, `save` →
  `always`, `source.messageID` / `callID` → tool ids; reply body uses `reply`
  (`once` / `always` / `reject`), not v1 `response`.
- Question: ViewModels pass `sessionId` so v2 can
  `POST /api/session/{sid}/question/{id}/reply|reject`; v1 ignores it.
- Abort: v2 `POST /api/session/{id}/interrupt` (204).
- Commands: v2 list has no `source` (treat as `.command`); run still posts
  `RunCommandRequest` to `POST /api/session/{id}/command`.
- Agents: v2 `id` → `MentionableAgent.name`; still drop `primary` / `hidden`.
- Models: join `/api/provider` + `/api/model`; skip disabled providers,
  `enabled == false`, and `deprecated`; empty `capabilities.input` keeps the
  v1 image+pdf default; `serverDefault` is `null` on v2.

**Why:** Permission dock, question dock, stop, slash commands, @ mentions, and
the model picker already consume those domain types. Live stores already listen
to remapped `permission.asked` / `question.*` from P3.

**Alternative rejected:** Fork docks or composer catalogs per protocol.

## V2 revert uses stage/clear, not commit (2026-08-12)

**Choice:** Map Commands undo to `POST /api/session/{id}/revert/stage`
(`messageID`, optional `partID`, `files: true`) and redo to
`POST /api/session/{id}/revert/clear`. Do not call `revert/commit`.

**Why:** v1 revert/unrevert is reversible. v2 commit permanently applies the
staged boundary and would break redo. Staging with `files: true` applies the
file changes the way v1 revert does.

**Alternative rejected:** Auto-commit after stage — irreversible, no redo.

## Deepen protocol remotes after the epic ships (2026-08-13)

**Choice:** Keep per-method `isV2()` dispatch inside existing repository
implementations for P0–P6. After `feature/ope-146-server-api-v2` is on `main`,
extract protocol-selected remotes (`V1SessionRemote` / `V2SessionRemote`, and
the same shape for other ports) behind the current repository interfaces.
Tracked as [OPE-155](https://linear.app/opencode-mobile/issue/OPE-155/isolate-v1-and-v2-http-remotes-behind-repository-ports).

**Why:** The repository port is already the right seam — Screen Models stay on
domain types and `ServerCapabilities`. The if/else forest is an implementation
smell inside a deep module, not protocol leaking into product UI. Extracting
remotes now would conflict with the stacked dual-stack PRs on both platforms
for no user-facing gain.

**Alternative rejected:** Swap `V1SessionRepository` / `V2SessionRepository` in
DI on connect (protocol is unknown at singleton construction; LiveStores hold
the bound instance). One `OpencodeServer` god facade. Protocol flags in Screen
Models.

## Protocol-or-probe lives on ActiveConnectionManager (2026-08-13)

**Choice:** Implicit protocol resolution is `ActiveConnectionManager.currentProtocolOrProbe()`:
cache, else probe the active connection through `ConnectionProbing` /
`ConnectionProbeService`, else v1. Session and Message HTTP dialects live in
`V1`/`V2` remotes behind a protocol-selected facade. Command, Filesystem, Model,
Agent, and Project keep per-method dispatch but share the same probe. Home
`probeServer()` still throws; Test Connection still probes the candidate
connection.

**Why:** Probe-on-miss is cache fill, so it belongs on the cache owner. Per-repo
`isV2()` copies stampeded probes. `ProjectRepository` / `HealthApi` cannot be
injected into ACM without a Hilt cycle. Two remote adapters make the dialect
seam real; the port adapter keeps error mapping and delete cleanup.

**Alternative rejected:** Inject `ProjectRepository` into ACM. Swap V1/V2
repositories in DI on connect. Extract remotes for every repository in this
change.

## V2 last-turn review is capability-gated (2026-08-13)

**Choice:** Screen Models read `ServerCapabilities.supportsSessionMessageDiff`
and omit last-turn Changes, the review pill, and tool-card last-turn
navigation on v2. Working-tree VCS diffs stay available. Repositories return
HTTP 404 for `getSessionDiff` on v2 without calling `GET /session/{id}/diff`.
Dead unused repository methods (`share`, `init`, children, sync send, get
message, list projects) stay; removing them is a large fake-update with no
v2 product effect.

**Why:** Upstream has no session message-diff twin. Degrade in product UI via
the existing capability flag rather than blocking v2 or forking Changes
ViewModels.

**Alternative rejected:** Leave last-turn visible and let the v1 path 404 —
would show a failed Changes load instead of a working-tree-only review.

## V2 session create decodes sparse Session.Info (2026-08-16)

**Choice:** Treat `title` and `location.directory` as optional when decoding
v2 `Session.Info`. Accept either `{ data: session }` or a bare session object.
If create, list, or get returns no title, use `New Session`.

**Why:** `POST /api/session` can return HTTP 200 without `title` (create payload
has no title field). Required-string decode then failed optimistic create after
a successful request. Live v2 (`0.0.0-beta-17498`) create and some list rows
omit `title` and include extra `subpath` / `agent` / `model`. Opening those
rows bound Session Chat with an empty header. Published OpenAPI marks `title`
optional.

**Alternative rejected:** Require the documented `{ data, title, location }`
shape — rejects working v2 servers that omit create-only fields.

## V2 message list omits order when following a cursor (2026-08-16)

**Choice:** First `GET /api/session/{id}/message` page sends `order=asc`.
Follow-up pages send only `cursor`. Do not combine them.

**Why:** Live v2 (`0.0.0-beta-17498`) returns `InvalidCursorError` 400
("Cursor cannot be combined with order") when both query params are present.
The cursor token already encodes order. That 400 aborted MessageSync reload
and left Session Chat on Syncing forever after a successful prompt.

**Alternative rejected:** Drop `order` entirely and reverse client-side —
first page would arrive newest-first and the timeline would flash inverted
until the full list was in memory.

## V2 subagent tool uses the child-session row (2026-08-16)

**Choice:** Treat v2 assistant content `name: "subagent"` as the same product
as v1 `task`. Map `state.metadata` (including `sessionID`) onto ToolPart
metadata. Do not copy the tool name into `title`. Pending/running/completed
parts use the Child Session Row; errors stay on the Tool Call Card.

**Why:** Live v2 (`0.0.0-beta-17498`) emits a generic tool named `subagent`
with `input.agent` / `input.description` and `state.metadata.sessionID`. The
timeline only promoted `task` and read `structured` + `sessionId`, so Android
and iOS showed "Called subagent" instead of the child-session row.

**Alternative rejected:** Rename v2 `subagent` to `task` in the adapter —
hides the wire name and still misses metadata/`sessionID` mapping.

## V2 abort interrupts timeline child sessions (2026-08-16)

**Choice:** Session Chat Stop posts interrupt for the bound session and every
child session id already on the timeline (`task` / `subagent` rows). After a
successful abort, clear optimistic Working and refresh GET `/api/session/active`.

**Why:** Live v2 runs a subagent on the child session. Interrupting only the
parent can 204 while the child keeps working, so Stop looks dead and Working
stays up. Timeline ids avoid `listChildSessions` (unused in ViewModel tests).
Idle-child 204 is harmless.

**Alternative rejected:** Interrupt only the focused session — parent Stop
cannot stop a running subagent.

## V2 completed compaction is not Compacting… (2026-08-16)

**Choice:** Parse v2 compaction `status`. `completed` snapshots are not an
in-flight turn. Clear local Commands compacting when a new completed
compaction id appears on the timeline.

**Why:** Live v2 (`0.0.0-beta-17498`) records `type: compaction` with
`status: completed` and a summary after Commands → compact. `POST
/api/session/{id}/compact` uses an infinite timeout, so Compacting… could
stay up after the server (and web) were already idle.

**Alternative rejected:** Keep treating any compaction marker in a busy turn
as in-flight — matches v1 mid-compact parts, but v2 history rows are
finished checkpoints.

## V2 todos seed from events only (2026-08-12)

**Choice:** `listSessionTodos` does not call a REST todo route on v2 and
throws `SessionError.Http(404)` instead of returning `[]`.
`DefaultSessionTodoLiveStore` already treats a failed seed as “keep current
cache,” so `todo.updated` events survive go-live and reconnect.

**Why:** Published v2 session API has no `GET .../todo` twin. Returning an
empty list would look like an authoritative snapshot and wipe event-seeded
todos. Event-only seed matches the research gap until upstream adds a REST
snapshot.

## V2 add-project starts at resolved location (2026-08-12)

**Choice:** Map `GET /path` to `GET /api/location` and `GET /file` to
`GET /api/fs/list`. `ServerPaths.home` is the location `directory` (no dedicated
home path on v2). Directory entries join relative `path` onto the listing
directory. V2 directory `path` values include a trailing separator (`src/`);
strip it before deriving the visible name and absolute path so Add Project does
not treat every folder as nameless and hide it.

**Why:** Add Project only needs a browse root plus child directories. Users can
still navigate up from the resolved location. Avoid inventing a home path the
server does not expose.

