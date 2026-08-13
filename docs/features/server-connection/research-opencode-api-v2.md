# Research: OpenCode API v1 (mobile) vs API v2

Date: 2026-08-12

Routing: lives under `server-connection` because this is server-protocol /
connection compatibility research for the mobile clients. It is not a
feature-implementation plan.

## Question

Which unprefixed (v1) HTTP APIs do opencode-mobile iOS and Android call today,
how do those map onto OpenCode 2.0 `/api/*` (v2), and for each used API how
close is the current mobile implementation to a workable v2 call?

## Summary verdict

Mobile is a **pure v1 client**. Every remote call uses unprefixed paths
(`GET /global/health`, `GET /global/event`, `GET /session`,
`POST /session/{id}/prompt_async`, …) with `?directory=` workspace scoping.

OpenCode 2 documents an intentional **breaking** server API
([Migrate from V1](https://opencode.ai/v2/docs/migrate-v1)): integrations must
move to `/api/...` and new clients (`@opencode-ai/client`). Desktop already
detects protocol via `/global/health` vs `/api/health` and runs a hybrid
compat layer.

**Overall closeness: far for product-critical paths.** Mean score across the
repository API surface is roughly **~2.3 / 5**, but several hard/blocked
endpoints are **repo-only dead weight** (see Product usage audit). The real
migration blockers that ship in UI today are still the **message + SSE model**,
**prompt_async**, **session status**, **todos**, **archive**, **path/file**,
and **historical session diffs** for Review Changes.

A v2-only server that does not mount legacy routes will fail connection probe
and every feature until mobile grows protocol detection + a domain adapter
(desktop’s `createCompatibleApi` / `detectServerProtocol` is the reference
pattern).

Score scale used below:

| Score | Meaning |
| --- | --- |
| 5 | Same contract aside from `/api` prefix |
| 4 | Same purpose; minor path/field renames |
| 3 | Same capability; moderate schema/flow changes |
| 2 | Capability exists; different model (scoping, IDs, split routes, wrappers) |
| 1 | Only loosely related; major redesign |
| 0 | No v2 equivalent / blocked for this use case |

## What mobile uses (inventory)

Authoritative sources: Android `data/remote/*Api.kt` and iOS
`Data/Remote/*APIClient.swift`. Both platforms expose the same surface unless
noted.

| # | Method | Mobile path | Purpose | Product use |
| --- | --- | --- | --- | --- |
| 1 | GET | `/global/health` | Connection test / home probe | **Used** |
| 2 | GET | `/global/event` | Foreground SSE bus | **Used** |
| 3 | GET | `/project` | List all projects | **Dead** (repo only; home uses `/project/current` per worktree) |
| 4 | GET | `/project/current` | Current project for a directory | **Used** |
| 5 | GET | `/session` | List sessions (`directory`) | **Used** |
| 6 | POST | `/session` | Create session | **Used** |
| 7 | GET | `/session/status` | Busy/idle map | **Used** |
| 8 | GET | `/session/{id}` | Session detail | **Used** |
| 9 | PATCH | `/session/{id}` | Rename / archive (`title`, `time.archived`) | **Used** |
| 10 | DELETE | `/session/{id}` | Delete session | **Used** |
| 11 | GET | `/session/{id}/children` | Fork children | **Dead** |
| 12 | GET | `/session/{id}/todo` | Session todos | **Used** |
| 13 | POST | `/session/{id}/init` | AGENTS.md init | **Dead** |
| 14 | POST | `/session/{id}/fork` | Fork at message | **Used** |
| 15 | POST | `/session/{id}/abort` | Stop work | **Used** |
| 16 | POST | `/session/{id}/share` | Share link | **Dead** |
| 17 | DELETE | `/session/{id}/share` | Unshare | **Dead** |
| 18 | GET | `/session/{id}/diff` | Historical message diff | **Used** (Review Changes “Last turn”) |
| 19 | POST | `/session/{id}/summarize` | Compaction | **Used** |
| 20 | POST | `/session/{id}/revert` | Revert to message | **Used** |
| 21 | POST | `/session/{id}/unrevert` | Clear revert | **Used** |
| 22 | GET | `/session/{id}/message` | Seed / reload messages | **Used** |
| 23 | GET | `/session/{id}/message/{mid}` | Single message | **Dead** |
| 24 | POST | `/session/{id}/message` | Sync send | **Dead** (composer uses `prompt_async`) |
| 25 | POST | `/session/{id}/prompt_async` | Primary async send | **Used** |
| 26 | GET | `/permission` | Pending permissions | **Used** |
| 27 | POST | `/session/{sid}/permissions/{pid}` | Approve/deny | **Used** |
| 28 | GET | `/question` | Pending questions | **Used** |
| 29 | POST | `/question/{id}/reply` | Answer question | **Used** |
| 30 | POST | `/question/{id}/reject` | Reject question | **Used** |
| 31 | GET | `/command` | Slash command catalog | **Used** |
| 32 | POST | `/session/{id}/command` | Run slash command | **Used** |
| 33 | GET | `/agent` | @-mention agents | **Used** |
| 34 | GET | `/config/providers` | Model picker catalog | **Used** |
| 35 | GET | `/vcs` | Branch info for Changes | **Used** |
| 36 | GET | `/vcs/diff` | Working-tree diffs (`mode=git`) | **Used** |
| 37 | GET | `/path` | Path discovery | **Used** (add-project) |
| 38 | GET | `/file` | List dir (`path=.` + `directory`) | **Used** (add-project) |

Shared query convention: most calls pass `directory=<absolute worktree>`.

## Product usage audit (hard APIs)

Method: search for call sites outside API clients, repository implementations,
protocol stubs, previews, and tests. “Used” means a ViewModel, LiveStore,
composer/commands region, or DI-wired MessageSync path invokes it.

### Repo-only / not product-used (safe to ignore for v2 migration urgency)

| API | Score | Evidence |
| --- | --- | --- |
| `GET /project` | 3 | `listProjects()` only in `ProjectRepository` / API; home loads via `getCurrentProject(worktree)` |
| `GET /session/{id}/children` | 1 | Only `listChildSessions` on repository |
| `POST /session/{id}/init` | 0 | Only `initializeSessionAgents` on repository |
| `POST/DELETE /session/{id}/share` | 0 | Only `shareSession` / `unshareSession` on repository |
| `GET /session/{id}/message/{mid}` | 2 | Only `MessageRepository.getMessage` |
| `POST /session/{id}/message` | 2 | Only `MessageRepository.sendMessage`; UI sends via `sendPromptAsync` |

### Hard / blocked APIs that **are** product-used

| API | Score | Call sites |
| --- | --- | --- |
| `GET /global/event` | 1 | `DefaultForegroundEventStream` → whole live stack |
| `GET /session/{id}/message` | 1 | `MessageSync` / message list fetcher (seed + recovery) |
| `POST /session/{id}/prompt_async` | 2 | `SessionChatComposer` |
| `GET /session/status` | 2 | Home load, `DefaultSessionStatusLiveStore`, session info |
| `GET /session` | 2 | Home / session live store / permission-question session lookup |
| `GET /config/providers` | 2 | Model picker + session context via `ModelRepository` |
| `GET /session/{id}/todo` | 1 | `DefaultSessionTodoLiveStore` |
| `PATCH /session/{id}` archive | 1 | Home archive action + session chat overflow |
| `GET /path`, `GET /file` | 1 / 3 | Add-project directory browser (`getPaths` + `listDirectory`) |
| `GET /session/{id}/diff` | 0 | Review Changes pill + Changes “Last turn” (`SessionChatReviewChanges`, `ChangesViewModel`) |
| `POST .../revert` + `/unrevert` | 2 | Session overflow commands |
| `POST .../summarize` | 3 | Compact command |

So three of the original “blocked (0)” items (**share**, **init**, and the
unused half of message send/get) do **not** block shipping a v2 client. The
one blocked API that **does** matter is **`GET /session/{id}/diff`** for
Review Changes last-turn.

SSE event types mobile actually interprets (non-exhaustive but critical):
`message.updated`, `message.part.updated`, `message.part.delta`,
`message.removed`, `message.part.removed`, `session.status`, `session.idle`,
`session.error`, `session.created`, `session.updated`, `permission.asked`,
`permission.replied`, `question.asked`, `question.replied`,
`question.rejected`, plus todo-related updates via live stores.

## Cross-cutting breaking changes

1. **Prefix and surface.** V2 public HttpApi is under `/api/...`. Official
   migrate doc: server API is an intentional break; use `@opencode-ai/client`.
2. **Protocol detection.** Desktop
   (`packages/app/src/utils/server-protocol.ts`):
   - `/global/health` with `{ healthy: true }` → **v1**
   - else `/api/health` with numeric `pid` → **v2**
   - `{ healthy: true }` on `/api/health` without `pid` treated as v1-shaped
3. **Response envelopes.** Many v2 routes return `{ data, cursor? }` or
   `Location.response(data)` instead of a bare array/object.
4. **Location model.** V2 prefers `location[directory]` / `location[workspace]`
   (deepObject) and/or `project` + `subpath`, not only `?directory=`.
5. **Session messages.** V1 `SessionV1.WithParts` (`info` + `parts[]`) vs v2
   projected `Session.Message.*` discriminated union (`user`, `assistant`
   with `content[]`, `compaction`, `shell`, …) and cursor pagination.
6. **Live transport.** `GET /global/event` → `GET /api/event`. Payload schema
   shifts toward durable `session.next.*` events; desktop still has unfinished
   work to drop v1 granular message/session events
   (`packages/app/V1_API_MIGRATION.md`).
7. **Beta instability.** Migrate doc warns v2 contracts may keep changing.

Local checkout note: `/Users/ivan/Projects/opencode/packages/protocol/src/groups/`
is a subset of what [published v2 docs](https://opencode.ai/v2/docs/api)
list (docs already document rename/fork/remove/command/project/vcs). Scores
below prefer **published docs + desktop migration mapping** as the user-facing
v2 target, and call out gaps when the local protocol package lags.

## Per-endpoint comparison and scores

### Connection and events

| Mobile API | V2 equivalent | Score | Why |
| --- | --- | --- | --- |
| `GET /global/health` | `GET /api/health` (`healthy`, `version`, `pid`) | **4** | Same gate; path + richer body. Probe must change or dual-probe. |
| `GET /global/event` | `GET /api/event` (volatile SSE of `V2Event`) | **1** | Same transport idea; event type/payload model is a redesign for mobile MessageSync / status / permission stores. |

### Projects and filesystem

| Mobile API | V2 equivalent | Score | Why |
| --- | --- | --- | --- |
| `GET /project` | `GET /api/project` | **3** | Same job; Project shape changes (`canonical`, `sandboxes`, icon/commands). |
| `GET /project/current` | `GET /api/project/current` | **3** | Same job; location-scoped; field renames (`worktree` → directory/canonical). |
| `GET /path` | Closest: `GET /api/location` (no dedicated `/api/path` in published index) | **1** | Capability moved into location resolution; not a drop-in. |
| `GET /file?path=.` | `GET /api/fs/list` (+ `fs.find` / `fs.read`) | **3** | Same listing intent; path + location query + entry schema change. |

### Sessions CRUD / lifecycle

| Mobile API | V2 equivalent | Score | Why |
| --- | --- | --- | --- |
| `GET /session` | `GET /api/session` → `{ data, cursor }` | **2** | Pagination + `Session.Info` (`location`, tokens, cost) vs flat list. |
| `POST /session` | `POST /api/session` (`agent`, `model`, `location`) | **3** | Create still exists; body and response wrapper differ. |
| `GET /session/status` | `GET /api/session/active` + execution events | **2** | Snapshot of running only; lose busy/retry nuance unless remapped from events. |
| `GET /session/{id}` | `GET /api/session/{sessionID}` → `{ data }` | **3** | Same read; schema/envelope change. |
| `PATCH /session/{id}` (title) | `POST /api/session/{sessionID}/rename` `{ title }` | **3** | Dedicated rename; method/path change; 204 vs Session body. |
| `PATCH /session/{id}` (`time.archived`) | Desktop target `POST .../archive`; **not** in published session index scraped 2026-08-12 | **1** | Archive semantics unclear on published v2; may need re-find. |
| `DELETE /session/{id}` | `DELETE /api/session/{sessionID}` (204, deletes children) | **4** | Close; status/boolean vs NoContent; children cascade is explicit. |
| `GET /session/{id}/children` | No dedicated children route in published session list; infer via `parentID` / list filters | **1** | Likely client-side filter or missing. |
| `GET /session/{id}/todo` | No REST todo route in published v2 session API; todos appear as events in schema inventory | **1** | Live-store REST seed has no clear twin. |
| `POST /session/{id}/init` | No v2 twin found | **0** | Out unless replaced by another workflow. |
| `POST /session/{id}/fork` | `POST /api/session/{sessionID}/fork` with `boundary: { type, messageID }` | **3** | Same capability; boundary object replaces bare `messageID`. |
| `POST /session/{id}/abort` | `POST /api/session/{sessionID}/interrupt` | **4** | Rename + NoContent; desktop compat maps abort→interrupt. |
| `POST/DELETE .../share` | **None** (desktop migration: blocked) | **0** | No sharing contract on current v2. |
| `GET /session/{id}/diff` | **None** (desktop: historical session diffs unavailable) | **0** | Review-from-message diffs blocked on v2 until semantics exist. |
| `POST /session/{id}/summarize` | `POST /api/session/{sessionID}/compact` | **3** | Same product action; body (provider/model) and response change. |
| `POST .../revert` + `/unrevert` | `.../revert/stage`, `/clear`, `/commit` | **2** | Three-step revert model vs single revert/unrevert. |

### Messages and prompting

| Mobile API | V2 equivalent | Score | Why |
| --- | --- | --- | --- |
| `GET /session/{id}/message` | `GET /api/session/{sessionID}/message` paginated projected messages | **1** | Entire MessageStore/DisplayMessage pipeline is v1-shaped. |
| `GET /session/{id}/message/{mid}` | `GET /api/session/{sessionID}/message/{messageID}` | **2** | Exists; payload is projected message, not WithParts. |
| `POST /session/{id}/message` (sync) | `POST /api/session/{sessionID}/prompt` | **2** | Admit+schedule model; returns pending user, not streamed WithParts. |
| `POST /session/{id}/prompt_async` | Same `POST .../prompt` (async admission) | **2** | Primary send path; body moves from `parts[]` to `text`/`files`/`agents`/`skills`/`delivery`. |

### Permissions and questions

| Mobile API | V2 equivalent | Score | Why |
| --- | --- | --- | --- |
| `GET /permission` | `GET /api/permission/request` | **3** | Path + Permission.Request schema (action/resources) changes. |
| `POST /session/{sid}/permissions/{pid}` | `POST /api/session/{sid}/permission/{requestID}/reply` `{ reply }` | **3** | Plural→singular, `/reply` suffix, `response`→`reply` (`once`/`always`/`reject`). |
| `GET /question` | `GET /api/question/request` | **3** | Path rename + location wrap. |
| `POST /question/{id}/reply` | `POST /api/session/{sid}/question/{id}/reply` | **3** | Session ID required in path. |
| `POST /question/{id}/reject` | `POST /api/session/{sid}/question/{id}/reject` | **3** | Same. |

### Commands, agents, models

| Mobile API | V2 equivalent | Score | Why |
| --- | --- | --- | --- |
| `GET /command` | `GET /api/command` | **4** | Prefix + location wrap. |
| `POST /session/{id}/command` | `POST /api/session/{sessionID}/command` (published) | **3** | Exists on published API; payload/admission differs (compat layer rewrites). |
| `GET /agent` | `GET /api/agent` | **4** | Prefix + location wrap; Agent.Info fields may differ. |
| `GET /config/providers` | `GET /api/provider` + `GET /api/model` (and optionally config) | **2** | Split catalog; mobile’s combined providers response must be rebuilt. |

### VCS / Changes

| Mobile API | V2 equivalent | Score | Why |
| --- | --- | --- | --- |
| `GET /vcs` | `GET /api/vcs` | **4** | Prefix + location envelope. |
| `GET /vcs/diff?mode=` | `GET /api/vcs/diff` (`mode`: `working`\|`branch`) | **4** | Desktop compat maps v1 `git` → v2 `working`; response wrapped in `{ location, data }`. |

## Highest-risk areas for mobile (product-used only)

1. **Message store + SSE (score ~1).** `MessageSync`, timeline projection,
   tool parts, compaction presence, and reconnect reload all assume v1 event
   names and `info`/`parts` REST seeds.
2. **Session status UX (score ~2).** Busy/Retrying mapping from
   `/session/status` + `session.status`/`session.idle` does not port 1:1 to
   `/api/session/active`.
3. **Prompt / attachments / slash commands (score ~2–3).** Composer must emit
   v2 prompt/command shapes; file parts and mentions change.
4. **Permissions / questions (score ~3).** Adaptable, but reply paths and
   schemas change and events likely rename.
5. **Review Changes last-turn diffs (score 0, product-used).**
   `GET /session/{id}/diff` powers the review pill and Changes “Last turn”.
   Working-tree VCS diffs (`/vcs`, `/vcs/diff`) remain viable (~4).
6. **Todos + archive + add-project path/file (scores 1–3).** Real UI features;
   not dead repository surface.
7. **Connection onboarding (score ~4 for health alone)** still needs dual
   probe + protocol flag before any other call.

Ignore for migration urgency (repo-only): share, init, children,
`getMessage`, sync `sendMessage`, `listProjects`.

## Migration difficulty (product view)

| Band | Product-used endpoints | Implication |
| --- | --- | --- |
| Near (4–5) | health, abort→interrupt, delete, command/agent list, vcs | Thin adapter |
| Medium (3) | `project/current`, rename, fork, compact, permission/question REST, file list | Dedicated DTOs + path map |
| Far (1–2) | session list/status, messages list, prompt_async, events, models catalog, archive, todos, path | Feature rewrites |
| Blocked (0) | session message-diff (`/session/{id}/diff`) | Wait on upstream or ship Changes without “Last turn” on v2 |

Practical approach mirrored by desktop:

1. Detect `v1` vs `v2` on connect.
2. Keep product domain types stable behind an adapter (do not leak raw v2
   schemas into UI).
3. Prioritize health + event + session list + messages + prompt.
4. Do not spend migration effort on dead repository methods; optionally delete
   them later.

## Sources

- Mobile clients:
  - `android/app/src/main/java/dev/opencode/mobile/data/remote/*.kt`
  - `ios/OpenCode Mobile/OpenCode Mobile/Data/Remote/*.swift`
- Official:
  - https://opencode.ai/v2/docs/migrate-v1 (Server API and clients)
  - https://opencode.ai/v2/docs/api and linked operation pages
  - https://opencode.ai/docs/server/ (v1 server / `/doc`)
- OpenCode repo (`/Users/ivan/Projects/opencode`):
  - `packages/protocol/src/groups/*` (v2 HttpApi groups)
  - `packages/opencode/src/server/routes/instance/httpapi/groups/*` (legacy unprefixed routes mobile uses)
  - `packages/app/V1_API_MIGRATION.md`
  - `packages/app/src/utils/server-protocol.ts`
  - `packages/app/src/utils/server-compat.ts`
  - `packages/client/src/generated/client.ts`
  - `packages/schema/src/event-manifest.ts`, `session-event.ts`
- Related prior note: `docs/features/terminal/research-desktop-terminal.md`

## Open questions

1. Do “better OpenCode” / `opencode2` builds still mount legacy unprefixed
   routes, or only `/api/*`? Desktop assumes hard protocol split via health
   probe; needs confirmation against the exact builds users run.
2. Where is session **archive** on published v2? Desktop checklist cites
   `POST /api/session/{id}/archive`, but it was not present in the published
   session operation index on 2026-08-12.
3. Is there a supported v2 REST replacement for **todos** (product-used), or
   only event/history projections? (`/session/{id}/children` is repo-only on
   mobile today.)
4. Will historical **`/session/{id}/diff`** return (needed for Review Changes
   “Last turn”), or should mobile keep only VCS working-tree diffs on v2?
5. Local `packages/protocol` lags published docs for several session routes—
   pin mobile work to a specific OpenCode commit/tag when implementing.
