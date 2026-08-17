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

**Choice:** P1 originally posted `{ messageID }`. Superseded 2026-08-16: live
published docs require `boundary`. See “V2 fork uses boundary”.

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
Text streaming uses synthetic `message.part.delta`. True idle is
`session.execution.succeeded|interrupted` (and unpublished
`session.compaction.ended|failed`). `session.next.step.ended` is not idle —
see “V2 step.ended is not idle” (2026-08-16).

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
- Commands: v2 list has no `source` (treat as `.command`); run posts the
  published v2 body (`command`, `arguments`, `model: {id, providerID, variant}`,
  `files[]`, `delivery: steer`), not v1 `parts[]`.
- Agents: v2 `id` → `MentionableAgent.name`; still drop `primary` / `hidden`.
- Models: join `/api/provider` + `/api/model`; skip disabled providers,
  `enabled == false`, and `deprecated`; empty `capabilities.input` keeps the
  v1 image+pdf default; `serverDefault` comes from `GET /api/model/default`
  when that model is in the catalog.

**Why:** Permission dock, question dock, stop, slash commands, @ mentions, and
the model picker already consume those domain types. Live stores already listen
to remapped `permission.asked` / `question.*` from P3.

**Alternative rejected:** Fork docks or composer catalogs per protocol.

## V2 revert uses stage/clear, not commit (2026-08-12)

**Choice:** Map Commands undo to `POST /api/session/{id}/revert/stage`
(`messageID`, `files: true`; no `partID`) and redo to
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

## V2 fork uses boundary (2026-08-16)

**Choice:** `POST /api/session/{id}/fork` sends
`{ boundary: { type: "before"|"through", messageID } }`. `before` when a
message id is present; `through` when it is blank. `messageID` is a string.

**Why:** Live published docs (2026-08-16) require `boundary`. Flat `{ messageID }`
is not the contract and 400s.

**Alternative rejected:** Keep the P1 OpenAPI-fixture body until a live 400
is observed — the published operation page is already the contract.

## V2 create sends title on the create body (2026-08-16)

**Choice:** `POST /api/session` sends `title` plus `location`. Do not follow up
with rename. Do not send `parentID` (not on the published create body). If the
server omits or changes the mapped title, copy the requested title onto the
returned session.

**Why:** Untitled create plus a second rename round-trip failed when create
returned sparse `Session.Info`. Published create accepts `title`.

## V2 session-scoped routes omit `directory` (2026-08-16)

**Choice:** Message list, prompt, agent/model switch, command run, and fork on
v2 do not send `?directory=` or `location[directory]`. Session list still uses
top-level `directory`. VCS and the command catalog still use
`location[directory]`.

**Why:** Session routes resolve location from the session. Extra query params
can 400 (`InvalidCursorError` was the live example for combining `order` with
`cursor`).

## V2 revert stage omits `partID` (2026-08-16)

**Choice:** Stage body is `{ messageID, files: true }` only.

**Why:** Published stage decode has no `partID`. Strict servers 400 on extra
keys.

## V2 archive is capability-gated (2026-08-16)

**Choice:** `ServerCapabilities.supportsSessionArchive` is true on v1 and false
on v2. Home and Session Chat hide Archive. The v2 remote throws HTTP 404
without calling `POST .../archive`.

**Why:** Published session index has no archive route. Showing the action would
404. Same degrade pattern as last-turn review.

## V2 live step.ended is not idle (2026-08-16)

**Choice:** `session.next.step.ended` projects `message.updated` (assistant) plus
`session.active.snapshot`. True idle remains `session.execution.succeeded` /
`interrupted` (and unpublished `session.compaction.ended|failed`). Status live
store refetches `GET /api/session/active` on `session.active.snapshot`.
MessageSync does not full-reload on that type.

**Why:** v2 turns are multi-step. Treating step-end as `session.idle` cleared
Working and reloaded history mid-turn.

## V2 live tool, shell, and compaction project into message parts (2026-08-16)

**Choice:** Map `session.next.tool.*`, `session.next.shell.*`, and
`session.next.compaction.*` into v1-shaped `message.part.updated` /
`message.part.delta`. Do not project live reasoning (v1 timeline parity).

**Why:** Without this, tools and compaction only appear after a full REST
reload, and that reload used to wait on a false idle.

## V2 SSE envelope accepts `event` and string `data` (2026-08-16)

**Choice:** Parse both Effect `{ type, data }` objects and OpenAPI
`{ id, event, data }` frames where `data` may be a JSON string. Nested
`properties` / `data` unwrap the same way.

**Why:** Generated SSE uses `event` + string `data`. Parsing only `{ type, data }`
dropped the live bus.

## V2 prompt files omit mime (2026-08-17)

**Choice:** `POST /api/session/{id}/prompt` and `.../command` file attachments
are `{ uri, name }` only. Do not send `mime`.

**Why:** Published `PromptInput.FileAttachment` is `uri`, `name`, `description`,
`mention`. Extra `mime` is the same extra-key class that 400'd `partID` on
revert. Mime belongs on history file payloads, not the request.

## V2 history files and shell output match published message shapes (2026-08-17)

**Choice:** Project user files from `uri`, `source.uri`, or inline `data` +
`mime` into a data URL. Decode shell `output` as a string or
`{ output, ... }` object.

**Why:** Message list user files are `{ data, mime, source }`. Shell rows use an
output object. Treating `output` as a required string failed the whole page.

## V2 permission and question tool ids use `id` (2026-08-17)

**Choice:** Map `source.id` / `tool.id` when `callID` is absent.

**Why:** Published permission and question sources use `{ messageID, id }`, not
v1 `callID`. Required `callID` decode dropped pending questions.

## Keep optimistic user rows until the timeline matches them (2026-08-17)

**Choice:** Merge pending send rows on every timeline apply, including
post-send REST reload. Clear them only after the projector reconciles a
matching authoritative user message, or when the send fails.

**Why:** `POST /api/session/{id}/prompt` admits an inbox item before
`GET /api/session/{id}/message` necessarily contains the user row. Dropping
the optimistic bubble on that reload made the sent message vanish, then
reappear when SSE or a later list included it. Quiet live refreshes already
merged pending rows; required post-send applies did not.

## V2 prompted applies user text on the live path (2026-08-17)

**Choice:** Project `session.next.prompted` (and admitted/inbox twins that
carry `prompt`) into `message.updated` plus user `message.part.updated` for
`prompt.text` and `prompt.files`. Keep info-only inserts unpublished.

**Why:** CLI and web prepend the user row from `event.data.prompt` as soon as
`session.next.prompted` arrives. Mobile previously kept only `{id, role}`,
and `shouldPublishLive` suppresses info-only slots, so the bubble and Working
under it waited on a later part or REST poll.

## V2 prompted type versions and late user order (2026-08-17)

**Choice:** Strip a trailing `.<digits>` from live event type names, unwrap
`sync.syncEvent`, and insert a late user row before a live assistant that is
not already preceded by a user.

**Why:** Durable `/api/event` frames can arrive as `session.next.prompted.1`
(or a sync wrapper). Assistant `session.text.*` is live-only, so Stop and the
response can paint first. Appending the user after that assistant showed the
reply above the prompt.

## V2 prompted keeps prompt across live envelope shapes (2026-08-17)

**Choice:** Resolve live event fields from `payload.properties`, flattened
top-level keys, and `payload.syncEvent` (not only `properties.syncEvent`).
Keep a `prompt` object on the current payload instead of unwrapping a nested
`data` sibling. Accept `prompt` as an object or JSON string.

**Why:** CLI send still arrived as info-only `message.updated` on the running
apps: GlobalBus durable frames put `syncEvent` next to `type`, and some
native frames flatten PromptFields beside `type`. Unwrapping nested `data`
dropped `prompt`, so `shouldPublishLive` waited for idle REST.

## V2 inbox.enqueued uses item.payload (2026-08-17)

**Choice:** Project `session.inbox.enqueued` / `session.inbox.delivered` user
text from `data.item.payload` (and `inboxID`) the same way
`session.next.prompted` uses `prompt` / `messageID`. Rebuild a live frame when
a changed message is missing from the last published snapshot. Keep the iOS
message poll running across a ConnectionEpoch bump (`continue`, not `return`).

**Why:** Live v2 `/api/event` admits the user row as
`{inboxID,sessionID,item:{type:"user",payload:{text,files}}}`. Reading only
`prompt` produced an info-only slot that `shouldPublishLive` suppressed, so
iOS stayed on the pre-turn timeline. Android hid the same gap with REST poll;
iOS poll exited on the idle-reconnect epoch race and never caught up.

## V2 model default and list paging (2026-08-17)

**Choice:** `GET /api/model/default` fills `serverDefault` when the model is in
the catalog. Session and message lists page until the cursor ends, with a 500
page safety cap and a repeated-cursor stop.

**Why:** The default route is published. A 20-page cap truncated long histories.

## V2 leftovers stay documented, not dual-pathed (2026-08-16)

**Choice:** Do not call published `/api/form/*`, durable
`GET /api/session/{id}/event|history`, or experimental session log in this
pass. Keep questions on `/api/question/*`. Leave reasoning unrendered. Do not
send prompt `skills[]`.

**Why:** Form reply `{ answer }` is a different product model than the question
dock. Durable catch-up routes are experimental; MessageSync already REST-reloads
messages on reconnect. Reasoning stays at v1 timeline parity. Skills need
composer UI. Home still has no documented `session.created` event.

## V2 question dock uses forms when question routes 404 (2026-08-17)

**Choice:** On v2, `GET /api/question/request` 404 falls back to
`GET /api/form/request`. `form.created|replied|cancelled` project to
`question.asked|replied|rejected`. Reply and dismiss for `frm_*` ids use
`POST /api/session/{id}/form/{id}/reply` with `{ answer: { q0: "…" } }` and
`POST .../form/{id}/cancel`. Keep `/api/question/*` when that list succeeds.

**Why:** Live v2 (`0.0.0-beta-17498`) serves the question tool as forms. A
captured reply body pinned `{ answer: { [fieldKey]: string } }`. The question
dock already collects selected labels; field keys default to `q0`, `q1`, …

## V2 live tool events omit `time.start` (2026-08-17)

**Choice:** Project envelope `created` onto `state.time.start` for
`session.tool.*` / `session.next.tool.*`. Normalize seconds / ms / µs. Keep the
first resolved start in the message store. Stamp local now only when a
pending/running tool still has no usable epoch.

**Why:** Live v2 tool payloads have no `time` field. `resolveProperties` prefers
`data` (session/message ids) and drops envelope `created`. Later `part.updated`
without time was wiping any start, so the Child Session Row stayed at `0:00`.

**Supersedes:** “do not call `/api/form/*`” in
[V2 leftovers](#v2-leftovers-stay-documented-not-dual-pathed-2026-08-16) for
the question dock only.

