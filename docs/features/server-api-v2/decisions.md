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
`EventDomainAdapter` and project `session.next.*` (plus `permission.v2.*` /
`question.v2.*`) into today’s `OpencodeEvent` types before MessageSync and
LiveStores see them. Text streaming uses synthetic `message.part.delta`;
`session.next.step.ended` becomes `session.idle` so reconnect-style full
reload remains the authority catch-up.

**Why:** MessageSync already applies `message.*` / `session.idle`. Desktop’s
session.next reducer is incomplete and uses unpublished type names; published
OpenAPI `session.next.*` is the contract.

**Alternative rejected:** Fork MessageSync per protocol, or copy desktop’s
non-published `session.text.delta` / `session.execution.*` labels.

## V2 prompt send keeps PromptSendOptions (2026-08-12)

**Choice:** Composer still builds `PromptSendOptions`. On v2 the router
optionally `POST .../agent` and `POST .../model`, then
`POST /api/session/{id}/prompt` with nested `{text,files,agents}` and
`delivery: steer`.

**Why:** Per-prompt agent/model are not on the v2 prompt body. Mentions and
attachments map onto `prompt.agents` / `prompt.files` (`uri` ← data URL).
v1 `prompt_async` + `parts[]` stays unchanged.

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
directory.

**Why:** Add Project only needs a browse root plus child directories. Users can
still navigate up from the resolved location. Avoid inventing a home path the
server does not expose.

