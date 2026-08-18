# Official OpenCode HTTP API v2 inventory

Date: 2026-08-17

Scope: public **OpenCode 2** HTTP API as documented for a mobile client that
remotely controls sessions. This is a source-cited inventory, not an
implementation plan.

Related in-repo research (mobile v1 usage vs v2, not a substitute for this
page): `../server-connection/research-opencode-api-v2.md`.

## Sources

Primary (fetched 2026-08-17):

| Source | Role |
| --- | --- |
| [https://opencode.ai/v2/docs/api](https://opencode.ai/v2/docs/api) | Generated HttpApi index (version `0.0.1`) |
| Linked pages under `/v2/docs/api/<group>/<operation-id>` | Per-route method, path, params, bodies, errors |
| [https://opencode.ai/v2/docs/migrate-v1](https://opencode.ai/v2/docs/migrate-v1) | V1→V2 breaking-change policy for **server API and clients** |
| [https://opencode.ai/v2/docs/build/client](https://opencode.ai/v2/docs/build/client) | Official TS client; same contract as the API reference |
| [https://opencode.ai/v2/docs/troubleshooting](https://opencode.ai/v2/docs/troubleshooting) | `opencode2 api`, `/api/health`, `OPENCODE_PASSWORD` |
| [https://opencode.ai/docs/server/](https://opencode.ai/docs/server/) | **V1** server page: `/doc` OpenAPI, Basic auth, unprefixed routes |

Official source used only where public docs omit event names / branded IDs
(snapshot `b6478dce` on [anomalyco/opencode](https://github.com/anomalyco/opencode);
the published docs are **newer** than this tree for several session mutations):

- [`packages/protocol/src/groups/*.ts`](https://github.com/anomalyco/opencode/tree/b6478dce/packages/protocol/src/groups)
- [`packages/schema/src/event-manifest.ts`](https://github.com/anomalyco/opencode/blob/b6478dce/packages/schema/src/event-manifest.ts)
- [`packages/schema/src/session-event.ts`](https://github.com/anomalyco/opencode/blob/b6478dce/packages/schema/src/session-event.ts)
- [`packages/schema/src/permission.ts`](https://github.com/anomalyco/opencode/blob/b6478dce/packages/schema/src/permission.ts)
- [`packages/schema/src/question.ts`](https://github.com/anomalyco/opencode/blob/b6478dce/packages/schema/src/question.ts)
- [`packages/schema/src/session-todo.ts`](https://github.com/anomalyco/opencode/blob/b6478dce/packages/schema/src/session-todo.ts)
- [`packages/schema/src/session-status-event.ts`](https://github.com/anomalyco/opencode/blob/b6478dce/packages/schema/src/session-status-event.ts)

**Not linked from the v2 HttpApi pages:** a live OpenAPI URL such as `GET /doc`
or `GET /api`. The v1 server page still documents `http://<host>:<port>/doc`
as OpenAPI 3.1 ([Server](https://opencode.ai/docs/server/)). The v2 client page
says types are generated from “the same contract as the API reference”
([Client](https://opencode.ai/v2/docs/build/client)) but does not publish a
spec URL. A running v2 server’s `/doc` (if mounted) would fill branded ID
formats, query codecs, and the SSE `data` JSON schema.

## How complete the public docs are

**Route catalog: high.** The index lists every operation with method + path.
Each linked page has summary text, path/query/body fields, success and error
shapes, and curl/JS/Python stubs.

**Field-level contract: medium.** Recurring renderer artifacts:

- Branded IDs (`Session.ID`, `Permission.ID`, …) render as type **`object`**
  with example `{}` instead of the string ID.
- Numeric query params (`limit`, `after`, `continue`, `follow`) render as
  **`string \| any`**.
- Location query params render as **`object \| any`** without documenting
  `directory` / `workspaceID` encoding.
- SSE `data` is typed as **`V2EventJsonString`** / **`SessionLogItemJsonString`**
  (opaque string). **Event type names and payloads are not listed** on
  [Subscribe to events](https://opencode.ai/v2/docs/api/event/v2-event-subscribe)
  or [Read the session log](https://opencode.ai/v2/docs/api/session/v2-session-log).

**Auth scheme: missing from HttpApi pages.** Every route documents `401
UnauthorizedError` `{ _tag, message }` but not Basic vs Bearer, header names,
or env vars. Adjacent v2 pages mention `OPENCODE_PASSWORD` and a Bearer
example (see [Auth](#auth)).

**Stability: experimental.** Index subtitle: “Experimental HttpApi surface for
selected instance routes.” Version **0.0.1**. Migrate doc: server API/clients
are still being finalized in beta
([Migrate from V1](https://opencode.ai/v2/docs/migrate-v1#server-api-and-clients)).

**Docs vs older GitHub protocol snapshot:** published docs include fork,
rename, delete, inbox, skill, command-on-session, experimental session log,
forms, worktrees, etc. The `b6478dce` `packages/protocol/src/groups/session.ts`
is a **subset** (and still mentions `/api/session/:id/history` and
`/api/session/:id/event`, which **404** on the docs site). Prefer the published
HttpApi pages over that snapshot for routes.

---

## Cross-cutting contract

### Base path

All documented v2 HTTP routes are under **`/api/...`**
([index](https://opencode.ai/v2/docs/api)). There is no unprefixed `/session`
surface on these pages.

Exceptions that keep the `/api` prefix but sit under **`/api/experimental/`**:

- `GET /api/experimental/session/{sessionID}/log`
- `POST /api/experimental/integration/wellknown`
- `GET /api/experimental/migration/v1`

Client construction uses the **server origin** as `baseUrl` (example
`http://localhost:4096`); paths already include `/api`
([Client](https://opencode.ai/v2/docs/build/client)).

V1 (for contrast) used unprefixed `/session`, `/global/health`, `/event`,
`/global/event` ([Server](https://opencode.ai/docs/server/)).

### Auth

| Claim | Source | Gap |
| --- | --- | --- |
| Protected routes return **401** `UnauthorizedError` | Every HttpApi page | Scheme not specified |
| Remote CLI: set **`OPENCODE_PASSWORD`** when connecting with `--server` | [Troubleshooting](https://opencode.ai/v2/docs/troubleshooting#explicit-servers) | Does not say HTTP Basic vs other |
| TS client example: `authorization: Bearer ${OPENCODE_TOKEN}` | [Client](https://opencode.ai/v2/docs/build/client) | Example only; token issuance not documented on HttpApi pages |
| Local service: `Service.headers(endpoint)` | [Client](https://opencode.ai/v2/docs/build/client) | Header contents not documented |
| V1 serve: **`OPENCODE_SERVER_PASSWORD`**, username default **`opencode`**, HTTP **basic auth** | [V1 Server](https://opencode.ai/docs/server/) | V1 page; not restated on v2 HttpApi |

HttpApi curl examples send **no** `Authorization` header.

### Envelopes, location, projects, worktrees

**Success lists** typically wrap `{ data, cursor? }` or `{ location, data }`.
**Mutations** often wrap `{ data: ... }` or return **204 No Content**.

**Location** is the workspace/project scope for catalogs and FS/VCS:

- `Location.Ref`: required `directory` (string); optional `workspaceID` (rendered as object).
- `Location.Info`: `directory`, optional `workspaceID`, required `project: { id, directory, canonical }`.

`GET /api/location` resolves a requested location or the server default
([Get location](https://opencode.ai/v2/docs/api/location/v2-location-get)).

**Project** identifiers are strings (`Project.Current.id`). List items also have
`canonical`, optional `vcs` (`git` \| `hg`), `sandboxes[]`, timestamps
([List projects](https://opencode.ai/v2/docs/api/project/v2-project-list),
[Get current project](https://opencode.ai/v2/docs/api/project/v2-project-current)).

**Session** carries `projectID`, `location` (`Location.Ref`), optional `subpath`.
List query can filter `directory`, `project`, `subpath`, `workspace`, `parentID`,
`search`, `order`, `limit`, `cursor`
([List sessions](https://opencode.ai/v2/docs/api/session/v2-session-list)).

**Worktrees** are managed per `projectID` under `/api/worktree/{projectID}`
(list/create/delete/refresh)
([index](https://opencode.ai/v2/docs/api)).

Location-scoped catalog routes (`/api/agent`, `/api/model`, `/api/command`,
`/api/skill`, `/api/provider`, `/api/fs/*`, `/api/vcs*`, `/api/form/request`,
`/api/permission/request`) take optional query `location`.

### Error model (typical)

Documented tags include `InvalidRequestError` (`message`, optional `kind`/`field`),
`UnauthorizedError`, `InvalidCursorError`, `SessionNotFoundError` (`sessionID`),
`MessageNotFoundError`, `PermissionNotFoundError`, `ConflictError`,
`ServiceUnavailableError`, `UnknownError`, plus domain tags (`CommandNotFoundError`,
`SkillNotFoundError`, `FormNotFoundError`, …). Many 404 unions are duplicated
in the renderer (same `_tag` twice).

### Experimental vs stable

| Label | Where |
| --- | --- |
| Whole HttpApi experimental, version 0.0.1 | [index](https://opencode.ai/v2/docs/api) |
| Group text “Experimental … routes” | plugin, session (**copy says “message routes”**), model, generate, provider, permission, filesystem, command, skill, event, pty, shell |
| Groups **without** “Experimental” in the index blurb | health, server, location, integration, mcp, credential, project, form, reference, worktree, vcs, debug, migration, websearch, config |
| Path-prefixed experimental | `/api/experimental/...` |

Beta policy: integrations **must** migrate off the V1 server API; contracts may
still change ([Migrate from V1](https://opencode.ai/v2/docs/migrate-v1#server-api-and-clients)).

---

## V1 vs V2 (docs)

From [Migrate from V1](https://opencode.ai/v2/docs/migrate-v1):

- Intentional breaks: plugin API, **server API and clients**, TUI config file.
- “Use `@opencode-ai/client`”; see generated API reference.
- Config `server` field is **ignored** in V2 (“use the V2 service and explicit
  server options; the server API is an intentional breaking change”).

From [V1 Server](https://opencode.ai/docs/server/) vs v2 HttpApi index:

| Concern | Typical V1 | Documented V2 |
| --- | --- | --- |
| Prefix | `/session`, `/global/health`, `/event` | `/api/session`, `/api/health`, `/api/event` |
| Health | `GET /global/health` → `{ healthy, version }` | `GET /api/health` → `{ healthy: true, version, pid }` |
| Events | `GET /event` or `GET /global/event`; first event `server.connected` | `GET /api/event` (volatile SSE); optional `GET /api/experimental/session/{id}/log` |
| Session list | `Session[]` | `{ data: Session.Info[], cursor }` |
| Status | `GET /session/status` map | `GET /api/session/active` map of running drains; **no** busy/idle/retry map on HttpApi |
| Send prompt | `POST .../prompt_async` (204) or sync `POST .../message` | `POST /api/session/{id}/prompt` admits inbox item; `resume` optional |
| Abort | `POST .../abort` | `POST .../interrupt` (optional query `continue`) |
| Rename | `PATCH /session/{id}` `{ title }` | `POST .../rename` `{ title }` → 204 |
| Archive | `PATCH` `time.archived` | **Not documented** as a dedicated route (`Session.Info.time.archived` exists on reads) |
| Fork | `{ messageID? }` | `{ boundary: { type: before\|through, messageID? } }` |
| Permissions | `POST /session/{sid}/permissions/{pid}` `{ response, remember? }` | `POST .../permission/{requestID}/reply` `{ reply: once\|always\|reject, message? }` |
| Questions | `/question` | **Both still published:** `GET /api/question/request` plus session `.../question/{id}/reply\|reject`, **and** forms (`/api/form`, `/api/session/{id}/form`). Mobile keeps question routes and falls back to forms on 404 ([decisions](decisions.md#v2-question-dock-uses-forms-when-question-routes-404-2026-08-17)). |
| Messages | `{ info, parts }[]` | Projected `Session.Message.Info` tagged union (no separate `parts` array) |
| Diffs | `GET /session/{id}/diff` | **No session-message diff route.** Working-tree: `GET /api/vcs/diff`. Revert staging returns `FileDiff.Info[]` |
| Todos | `GET /session/{id}/todo` | **No todo HTTP route** on the v2 index |
| Models | `GET /config/providers` | `GET /api/model`, `GET /api/provider`, `GET /api/model/default` |
| FS | `/file`, `/path`, `/find/file` | `/api/fs/list`, `/api/fs/read/*`, `/api/fs/find` |
| OpenAPI | `GET /doc` | **Not mentioned** on v2 HttpApi pages |

---

## Auth, health, server, location, migration

Auth: see [Auth](#auth). All of these document 401.

### `GET /api/health` — Check server health

- [Page](https://opencode.ai/v2/docs/api/health/v2-health-get)
- Body: none
- **200 `ServiceHealth`:** `healthy` (literal `true`), `version` (string), `pid` (rendered object)
- Index group: health (not labeled experimental)

### `POST /api/service/stop` — Stop the managed server

- [Page](https://opencode.ai/v2/docs/api/health/v2-health-stop)
- Body: none (not extracted in detail here; see page)
- Index: health

### `GET /api/server` — Get server information

- [Page](https://opencode.ai/v2/docs/api/server/v2-server-get)
- **200:** `{ urls: string[] }` — “URLs that can be used to connect to this server”

### `GET /api/location` — Get location

- [Page](https://opencode.ai/v2/docs/api/location/v2-location-get)
- Query: `location` optional
- **200 `Location.Info`**

### `GET /api/experimental/migration/v1` — Get V1 migration status

- [Page](https://opencode.ai/v2/docs/api/migration/v2-experimental-migration-v1-status)
- **200 union:** `{ status: "required" \| "completed" }` **or** `{ status: "running", progress: { label, numerator, denominator } }` **or** `{ status: "error", error }`
- Path is experimental; describes **session history** migration progress

---

## Session

Index: [session group](https://opencode.ai/v2/docs/api) (labeled “Experimental
message routes” even though most routes are session-level).

Shared **`Session.Info`** (list/get/create/fork/import responses):

- `id` (branded; docs: object)
- optional `parentID`, optional `fork: { sessionID, boundary }`
- `boundary` union: `{ type: "before", messageID }` \| `{ type: "through", messageID }`
- required `projectID` (string)
- optional `agent` (string), optional `model: Model.Ref { id, providerID, variant? }`
- required `cost` (`Money.USD`), `tokens` (`input`, `output`, `reasoning`, `cache.read/write`)
- required `time: { created, updated, archived? }`
- optional `title`
- required `location: Location.Ref`
- optional `subpath`
- optional `revert: Session.Revert` (`messageID`, optional `partID`, `snapshot`, `files[]` as `FileDiff.Info`)

`FileDiff.Info`: `file`, `patch`, `additions`, `deletions`, `status`: `added` \| `deleted` \| `modified`.

### `GET /api/session` — List sessions

- [Page](https://opencode.ai/v2/docs/api/session/v2-session-list)
- Query (all optional in docs): `workspace`, `limit`, `order`, `search`, `parentID`, `directory`, `project`, `subpath`, `cursor`
- **200 `SessionsResponse`:** `{ data: Session.Info[], cursor: { previous?, next? } }`
- Errors: 400 `InvalidCursorError` \| `InvalidRequestError`, 401

Protocol source (older) documents `order` as `asc` \| `desc`, `limit` default newest 50
([session.ts](https://github.com/anomalyco/opencode/blob/b6478dce/packages/protocol/src/groups/session.ts)).

### `POST /api/session` — Create session

- [Page](https://opencode.ai/v2/docs/api/session/v2-session-create)
- Body (all optional except as marked in renderer): `id?`, `title?`, `agent?`, `model?` (`Model.Ref`), `location?` (`Location.Ref`; `directory` required if present)
- **200:** `{ data: Session.Info }`

Client example uses `{ location: { directory: "/workspace" } }`
([Client](https://opencode.ai/v2/docs/build/client)).

### `POST /api/session/import` — Import session

- [Page](https://opencode.ai/v2/docs/api/session/v2-session-import)
- Body: `info` (`Session.Info`), `messages` (`Session.Message.Info[]`), optional `location`
- **200:** `{ data: Session.Info }`; 409 `ConflictError`

### `GET /api/session/{sessionID}/export` — Export session

- [Page](https://opencode.ai/v2/docs/api/session/v2-session-export)
- Query: `sanitize?`
- **200:** `{ data: SessionTransfer.Data }` with `info` + `messages`

### `GET /api/session/active` — List active sessions

- [Page](https://opencode.ai/v2/docs/api/session/v2-session-active)
- **200:** `{ data: object }` — “foreground Session drains currently owned by this OpenCode process. Sessions absent from the result are inactive.”
- Protocol source: `Record<Session.ID, { type: "running" }>`
  ([session.ts](https://github.com/anomalyco/opencode/blob/b6478dce/packages/protocol/src/groups/session.ts)).
- **Not** a full idle/busy/retry status map (that v1 `GET /session/status` shape is not on this page).

### `GET /api/session/{sessionID}` — Get session

- [Page](https://opencode.ai/v2/docs/api/session/v2-session-get)
- **200:** `{ data: Session.Info }`; 404 `SessionNotFoundError`

### `DELETE /api/session/{sessionID}` — Delete session

- [Page](https://opencode.ai/v2/docs/api/session/v2-session-remove)
- “Delete a session and its child sessions.” **204**

### `POST /api/session/{sessionID}/fork` — Fork session

- [Page](https://opencode.ai/v2/docs/api/session/v2-session-fork)
- Body required: `boundary`: `{ type: "before", messageID }` **or** `{ type: "through" }` (through variant’s `messageID` required in `Session.ForkBoundary` on the session object; request type is `Session.ForkRequestBoundary`)
- **200:** `{ data: Session.Info }`; 404 session or message

### `POST /api/session/{sessionID}/agent` — Switch session agent

- [Page](https://opencode.ai/v2/docs/api/session/v2-session-switchagent)
- Body: `{ agent: string }` required. **204**

### `POST /api/session/{sessionID}/model` — Switch session model

- [Page](https://opencode.ai/v2/docs/api/session/v2-session-switchmodel)
- Body: `{ model: Model.Ref }` (`id`, `providerID` required, `variant` optional). **204**

### `POST /api/session/{sessionID}/rename` — Rename session

- [Page](https://opencode.ai/v2/docs/api/session/v2-session-rename)
- Body: `{ title: string }` required. **204**

### `POST /api/session/{sessionID}/move` — Move session

- [Page](https://opencode.ai/v2/docs/api/session/v2-session-move)
- Body: `directory` required; optional `workspaceID`, `delivery`. **204**

### `POST /api/session/{sessionID}/prompt` — Send message

- [Page](https://opencode.ai/v2/docs/api/session/v2-session-prompt)
- Description: “Durably admit one session input and schedule agent-loop execution unless `resume` is false.”
- Body:
  - `id?` (message/inbox id; docs: object)
  - **`text` required** (string) — not a `parts[]` array
  - `files?`: `{ uri, name?, description?, mention?: { start, end, text } }`
  - `agents?`: `{ name, mention? }`
  - `skills?`: `{ id, mention? }`
  - `metadata?` object
  - `delivery?`: `Session.Inbox.Delivery` — allowed on response: **`steer` \| `queue`**
  - `resume?` boolean
- **200:** `{ data: Session.Inbox.User }` (`id`, `sessionID`, `timeCreated`, `type: "user"`, `payload`, `delivery`)
- Errors include **409 ConflictError** (does not wait for the model turn)
- Client: `client.session.prompt({ sessionID, text })`
  ([Client](https://opencode.ai/v2/docs/build/client))

This is **admit-and-schedule**, not v1 sync `POST /session/{id}/message` and not
named `prompt_async`. Live tokens come from the event stream / session log, not
this response.

### `POST /api/session/{sessionID}/command` — Run command

- [Page](https://opencode.ai/v2/docs/api/session/v2-session-command)
- “Resolve a slash command into prompt input, admit it durably…”
- Body: **`command` required**; optional `id`, `arguments`, `agent`, `model`, `files`, `agents`, `skills`, `delivery`, `resume`
- **200:** `{ data: Session.Inbox.User }`; 404 `CommandNotFoundError`; 500 `CommandEvaluationError`; 409 conflict

### `POST /api/session/{sessionID}/skill` — Activate skill

- [Page](https://opencode.ai/v2/docs/api/session/v2-session-skill)
- Body: **`skill` required**; optional `id`, `resume`. **204**; 404 `SkillNotFoundError`

### `POST /api/session/{sessionID}/synthetic` — Add synthetic message

- [Page](https://opencode.ai/v2/docs/api/session/v2-session-synthetic)
- Body: **`text` required**; optional `id`, `description`, `metadata`, `delivery`, `resume`
- **200:** `{ data: Session.Inbox.Synthetic }`

### `POST /api/session/{sessionID}/shell` — Run shell command

- Index: [session shell](https://opencode.ai/v2/docs/api). Location-scoped **process** shells are a separate `/api/shell` group. Session shell admits a shell **message** into the session (see [Run shell command](https://opencode.ai/v2/docs/api/session/v2-session-shell) for body).

### `POST /api/session/{sessionID}/compact` — Compact session

- [Page](https://opencode.ai/v2/docs/api/session/v2-session-compact)
- “Queue a durable session compaction request.”
- Body: optional `id`, `delivery`
- **200:** `{ data: Session.Inbox.Compaction }` (`type: "compaction"`); 409 conflict

### `POST /api/session/{sessionID}/wait` — Wait for session

- [Page](https://opencode.ai/v2/docs/api/session/v2-session-wait)
- “Wait for a session agent loop to become idle.” **204**; 503 `ServiceUnavailableError`

### Revert (stage / clear / commit)

- [Stage](https://opencode.ai/v2/docs/api/session/v2-session-revert-stage): `POST .../revert/stage` body `{ messageID, files? }` → **200** `{ data: Session.Revert }`; 409 `SessionBusyError`
- [Clear](https://opencode.ai/v2/docs/api/session/v2-session-revert-clear): `POST .../revert/clear` → **204**
- [Commit](https://opencode.ai/v2/docs/api/session/v2-session-revert-commit): `POST .../revert/commit` → **204**

No v1-style `unrevert` name; **clear** is the documented undo of a staged revert.

### `GET /api/session/{sessionID}/context` — Get session context

- [Page](https://opencode.ai/v2/docs/api/session/v2-session-context)
- “All messages after the last compaction.” **200:** `{ data: Session.Message.Info[] }` (no cursor)

### Inbox

- [List](https://opencode.ai/v2/docs/api/session/v2-session-inbox-list): `GET .../inbox` → `{ data: Session.Inbox.Info[] }` union `user` \| `synthetic` \| `compaction` \| `move`; each has `delivery: steer \| queue`
- [Cancel](https://opencode.ai/v2/docs/api/session/v2-session-inbox-cancel): `DELETE .../inbox/{inboxID}`
- [Steer](https://opencode.ai/v2/docs/api/session/v2-session-inbox-steer): `POST .../inbox/{inboxID}/steer` → **204**
- [Queue](https://opencode.ai/v2/docs/api/session/v2-session-inbox-queue): `POST .../inbox/{inboxID}/queue`

### Instructions entries

Index: `GET/PUT/DELETE /api/session/{sessionID}/instructions/entries[/{key}]`
([index](https://opencode.ai/v2/docs/api)).

### `POST /api/session/{sessionID}/generate` — Generate text from session context

Index only in this inventory; one-shot from session context
([page](https://opencode.ai/v2/docs/api/session/v2-session-generate)). Separate
global `POST /api/generate`.

### `GET /api/experimental/session/{sessionID}/log` — Read the session log

- [Page](https://opencode.ai/v2/docs/api/session/v2-session-log)
- **Experimental.** “Reads events after an exclusive aggregate sequence and continues with live events when `follow=true`.”
- Query: `after?`, `follow?`
- **200 SSE-shaped:** `{ id, event, data: SessionLogItemJsonString }`
- Payload types **not expanded** on the page

### `POST /api/session/{sessionID}/interrupt` — Interrupt session execution

- [Page](https://opencode.ai/v2/docs/api/session/v2-session-interrupt)
- “Interrupt active execution owned by this OpenCode process. Idle interruption is a no-op. When `continue=true`, execution resumes pending steering input while queued work remains parked.”
- Query: `continue?`
- **204** — this is the documented replacement for v1 **abort**

### `POST /api/session/{sessionID}/background` — Background blocking session tools

- [Page](https://opencode.ai/v2/docs/api/session/v2-session-background)
- **204**; idle is a no-op

**Not on the published index:** `PATCH` session, archive-only mutation, `GET
.../todo`, `GET .../diff`, `GET .../children`, `POST .../abort`, `POST
.../prompt_async`, `GET .../history`, `GET .../event` (session-scoped SSE under
non-experimental path).

---

## Messages (projected)

There is **no** `/api/message` collection. Messages hang off the session.

### Model vs v1 `parts`

V2 **projected** messages are a **tagged union** on `type`, not `{ info, parts }`.

Documented `Session.Message.Info` variants
([Get session messages](https://opencode.ai/v2/docs/api/session/v2-session-message)):

| `type` | Notable fields |
| --- | --- |
| `agent-switched` | `agent`, `previous?` |
| `model-switched` | `model: Model.Ref`, `previous?` |
| `location-switched` | `location`, `projectID?`, `subpath?`, `previous?` |
| `user` | `text`, `files[]`, `agents[]`, `skills[]` (attachments, not generic parts) |
| `synthetic` | `text`, `description?` |
| `system` | `text`, `description?` |
| `skill` | `skill`, `name`, `text` |
| `shell` | `shellID`, `command`, `status` (`running` \| `exited` \| `timeout` \| `killed`), `exit`, `output` |
| `assistant` | `agent`, `model`, `content[]` of `text` \| `reasoning` \| `tool`, finish/cost/tokens/error/retry |
| `compaction` | `status` `running` \| `completed` \| `failed`; `reason` `auto` \| `manual` |

Assistant **tool** state: `streaming` (input string) \| `running` \| `completed` (tool content) \| `error`.

User file attachments on **read** use `Prompt.FileAttachment` with `data` (base64), `mime`, `source` (`inline` \| `uri`). **Send** uses `uri` (+ optional mention), not raw parts.

### `GET /api/session/{sessionID}/message` — Get session messages

- [Page](https://opencode.ai/v2/docs/api/session/v2-session-message)
- Query: `limit?`, `order?`, `cursor?`
- **200 `SessionMessagesResponse`:** `{ data: Session.Message.Info[], cursor }`
- 500 `UnknownError`

Protocol source: `order` `asc` \| `desc`; `limit` 1–200; do not combine `cursor` with `order`
([message.ts](https://github.com/anomalyco/opencode/blob/b6478dce/packages/protocol/src/groups/message.ts)).

### `GET /api/session/{sessionID}/message/{messageID}` — Get session message

- [Page](https://opencode.ai/v2/docs/api/session/v2-session-message)
- **200:** `{ data: Session.Message.Info }`

No documented `POST` to create a message except via prompt/command/synthetic/skill/shell/inbox.

---

## Events / SSE

### `GET /api/event` — Subscribe to events

- [Page](https://opencode.ai/v2/docs/api/event/v2-event-subscribe)
- Group labeled experimental
- “Subscribe to native event payloads for the server. **Volatile by contract:** a slow consumer overflows and fails the stream, and events during disconnection are missed.”
- **200:** `{ id, event: string, data: V2EventJsonString }` (docs example `data: "string"`)
- Client: `for await (const event of client.event.subscribe()) { event.type }`
  ([Client](https://opencode.ai/v2/docs/build/client)) — implies decoded objects with a **`type`** field, not only the SSE `event` name

**Public docs do not list event type strings or JSON schemas.**

Source fill-in (`EventManifest.ServerDefinitions` + protocol `event.ts` always
adds `server.connected` if missing):

[event-manifest.ts](https://github.com/anomalyco/opencode/blob/b6478dce/packages/schema/src/event-manifest.ts)
[event.ts](https://github.com/anomalyco/opencode/blob/b6478dce/packages/protocol/src/groups/event.ts)

`ServerDefinitions` includes: ModelsDev, Integration, Catalog, **durable V1
session events**, **`SessionEvent` (`session.next.*`)**, filesystem, reference,
**`permission.v2.*`**, plugin, project directories, filesystem watcher, PTY,
**`question.v2.*`**, **`todo.updated`**.

It does **not** include (those live on the wider `Definitions` inventory, not
the HTTP server stream in this snapshot): `session.status`, `session.idle`,
MCP, VCS, worktree, TUI, Permission V1, Question V1, `global.disposed`, etc.

#### Session live/durable types (`session.next.*`)

From [session-event.ts](https://github.com/anomalyco/opencode/blob/b6478dce/packages/schema/src/session-event.ts).
Common fields: `timestamp`, `sessionID`. Durable events also carry aggregate
metadata in the event wrapper (`durable.aggregateID/seq/version` in protocol
`event.ts` `fields`).

| Type | Notes |
| --- | --- |
| `session.next.agent.switched` | `messageID`, `agent` |
| `session.next.model.switched` | `messageID`, `model` |
| `session.next.moved` | `location`, `subdirectory?` |
| `session.next.prompted` | `messageID`, `prompt`, `delivery` |
| `session.next.prompt.admitted` | same |
| `session.next.context.updated` | `messageID`, `text` |
| `session.next.synthetic` | `messageID`, `text` |
| `session.next.shell.started` / `.ended` | `callID`, command/output |
| `session.next.step.started` / `.ended` / `.failed` | assistant turn |
| `session.next.text.started` / `.delta` / `.ended` | **delta is live-only** (not in DurableDefinitions) |
| `session.next.reasoning.started` / `.delta` / `.ended` | delta live-only |
| `session.next.tool.input.started` / `.delta` / `.ended` | delta live-only |
| `session.next.tool.called` / `.progress` / `.success` / `.failed` | |
| `session.next.retried` | |
| `session.next.compaction.started` / `.delta` / `.ended` | delta live-only |
| `session.next.revert.staged` / `.cleared` / `.committed` | |

#### Permission / question / todo (schema)

| Type | Payload (schema) | Source |
| --- | --- | --- |
| `permission.v2.asked` | `Permission.Request` fields | [permission.ts](https://github.com/anomalyco/opencode/blob/b6478dce/packages/schema/src/permission.ts) |
| `permission.v2.replied` | `sessionID`, `requestID`, `reply` | same |
| `question.v2.asked` / `.replied` / `.rejected` | question request / answers | [question.ts](https://github.com/anomalyco/opencode/blob/b6478dce/packages/schema/src/question.ts) |
| `todo.updated` | `sessionID`, `todos[]` | [session-todo.ts](https://github.com/anomalyco/opencode/blob/b6478dce/packages/schema/src/session-todo.ts) |
| `server.connected` | `{}` | [server-event.ts](https://github.com/anomalyco/opencode/blob/b6478dce/packages/schema/src/server-event.ts) + protocol group |

**Published HttpApi documents both question routes and forms.** Question HTTP
is still on the docs site:

- [List pending question requests](https://opencode.ai/v2/docs/api/question/v2-question-request-list) — `GET /api/question/request`
- [Reply](https://opencode.ai/v2/docs/api/question/v2-session-question-reply) — `POST /api/session/{sessionID}/question/{requestID}/reply` `{ answers: string[][] }`
- [Reject](https://opencode.ai/v2/docs/api/question/v2-session-question-reject) — `POST /api/session/{sessionID}/question/{requestID}/reject`

Forms are an additional surface (`/api/form/*`), not a completed replacement.
Mobile keeps the question dock on `/api/question/*` until form `{ answer }` is
pinned ([decisions](decisions.md#v2-leftovers-stay-documented-not-dual-pathed-2026-08-16)).
`question.v2.*` event names are from schema source, not the public subscribe
page. Form event names are also unpublished.

V1 names mobile uses today (`message.updated`, `message.part.delta`,
`permission.asked`, `session.status`, …) are **not** the v2 `session.next.*` /
`permission.v2.*` names.

### Session log SSE

See [experimental session log](#get-apiexperimentalsessionsessionidlog--read-the-session-log).
This is the documented **durable + follow** stream. Public docs still do not
enumerate `SessionLogItemJsonString`.

---

## Permission

Index: experimental permission routes
([index](https://opencode.ai/v2/docs/api)).

**Request model (`Permission.Request`):**

- `id`, `sessionID` (branded / object in docs)
- **`action` string required**
- **`resources` string[] required**
- optional `save` string[] (patterns to persist on `always`)
- optional `metadata` object
- optional `source`: `{ type: "tool", messageID, id }` (docs). Schema snapshot uses `callID` instead of `id`
  ([permission.ts](https://github.com/anomalyco/opencode/blob/b6478dce/packages/schema/src/permission.ts)) —
  **docs vs source field name may differ; trust `/doc` on a live server.**

**Reply:** `once` \| `always` \| `reject` plus optional `message`
([Reply](https://opencode.ai/v2/docs/api/permission/v2-session-permission-reply)).
No `remember` boolean (v1). `always` is the persist path; `once` is single-use.
Mobile v1 product rule “do not persist remember from mobile” maps to **avoid
`always`**, use `once` / `reject`.

Config-side permission **actions** also renamed in V2 (`bash`→`shell`, etc.)
([Migrate from V1](https://opencode.ai/v2/docs/migrate-v1#permissions-and-tools))
— that is config, not the HTTP reply enum.

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/api/permission/request` | Pending for a **location**; `{ location, data: Request[] }` ([page](https://opencode.ai/v2/docs/api/permission/v2-permission-request-list)) |
| GET | `/api/permission/saved` | Saved rules; optional `projectID` ([page](https://opencode.ai/v2/docs/api/permission/v2-permission-saved-list)) |
| DELETE | `/api/permission/saved/{id}` | Remove saved ([page](https://opencode.ai/v2/docs/api/permission/v2-permission-saved-remove)) |
| GET | `/api/session/{sessionID}/permission` | Pending for session ([page](https://opencode.ai/v2/docs/api/permission/v2-session-permission-list)) |
| POST | `/api/session/{sessionID}/permission` | **Create/evaluate** request (server/tool side); body `action`, `resources`, optional `id`, `save`, `metadata`, `source`, `agent`. **200** `{ data: { id, effect: allow\|deny\|ask } }` ([page](https://opencode.ai/v2/docs/api/permission/v2-session-permission-create)) |
| GET | `/api/session/{sessionID}/permission/{requestID}` | Get one ([page](https://opencode.ai/v2/docs/api/permission/v2-session-permission-get)) |
| POST | `/api/session/{sessionID}/permission/{requestID}/reply` | User reply; **204** |

Create-permission is not the mobile approve path; **reply** is.

---

## Form (additional to still-published questions)

Question routes remain on the HttpApi (see [Permission / question / todo](#permission--question--todo-schema)).
Forms are a parallel, richer field model:

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/api/form/request` | Pending for location; `{ location, data: Form.Info[] }` ([page](https://opencode.ai/v2/docs/api/form/v2-form-request-list)) |
| GET | `/api/session/{sessionID}/form` | List session forms |
| POST | `/api/session/{sessionID}/form` | Create (title, fields, …) |
| GET | `/api/session/{sessionID}/form/{formID}` | Get |
| GET | `/api/session/{sessionID}/form/{formID}/state` | State |
| POST | `/api/session/{sessionID}/form/{formID}/reply` | `{ answer: Form.Answer }` — **Answer shape not expanded** (example `{}`) ([page](https://opencode.ai/v2/docs/api/form/v2-session-form-reply)) |
| POST | `/api/session/{sessionID}/form/{formID}/cancel` | Cancel |

`Form.Info`: `id`, `sessionID`, `title`, optional `metadata`, `fields` (string /
number / integer / … with `when` conditions, options, validation). Errors:
`FormInvalidAnswerError`, `FormNotFoundError`, `FormAlreadySettledError`.

---

## Command and skill catalogs

### `GET /api/command` — List commands

- [Page](https://opencode.ai/v2/docs/api/command/v2-command-list)
- Query: `location?`
- **200:** `{ location: Location.Info, data: Command.Info[] }`
- `Command.Info`: `name`, `template`, optional `description`, `agent`, `model`, `subtask`

Run via `POST /api/session/{id}/command` (above).

### `GET /api/skill` — List skills

- [Page](https://opencode.ai/v2/docs/api/skill/v2-skill-list)
- **200:** `{ location, data: Skill.Info[] }`
- `Skill.Info`: `id`, `name`, `description?`, `slash?`, `autoinvoke?`, `location`, `content`

Activate via `POST /api/session/{id}/skill`.

---

## Agent, model, provider

Location-scoped; experimental model/provider groups.

### `GET /api/agent` / `GET /api/agent/{agentID}`

- [List](https://opencode.ai/v2/docs/api/agent/v2-agent-list): `{ location, data: Agent.Info[] }`
- `Agent.Info`: `id`, `name`, optional `model`, `request` (settings/headers/body), `system?`, `description?`, `mode`: `subagent` \| `primary` \| `all`, `hidden`, `color?`, `steps?`, **`permissions: Permission.Rule[]`** (`action`, `resource`, `effect`: `allow` \| `deny` \| `ask`)

### `GET /api/model` / `GET /api/model/default`

- [List](https://opencode.ai/v2/docs/api/model/v2-model-list): snapshot may precede plugin settlement; 503 possible
- [Default](https://opencode.ai/v2/docs/api/model/v2-model-default): model used when session has no explicit model; `data` may be nullish (`Model.Info | any`)

`Model.Ref` used on sessions: `{ id, providerID, variant? }` (this `id` is the
catalog/model selector id, distinct from `modelID` on `Model.Info`).

### `GET /api/provider` / `GET /api/provider/{providerID}`

- [List](https://opencode.ai/v2/docs/api/provider/v2-provider-list): `activation` `auto` \| `enabled` \| `disabled`

V1 `GET /config/providers` is **not** on the v2 index.

---

## Project, filesystem, VCS, worktree

### Project

- `GET /api/project` — [list](https://opencode.ai/v2/docs/api/project/v2-project-list) (array of `Project`, not `{ data }` in the renderer)
- `GET /api/project/current` — [current](https://opencode.ai/v2/docs/api/project/v2-project-current) query `location?` → `Project.Current` `{ id, directory, canonical }`

### Filesystem (experimental, location-scoped)

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/api/fs/list` | Query `location?`, `path?`; `{ location, data: { path, type: file\|directory }[] }` ([page](https://opencode.ai/v2/docs/api/filesystem/v2-fs-list)) |
| GET | `/api/fs/find` | `query` required; `type?` file\|directory; `limit?` ([page](https://opencode.ai/v2/docs/api/filesystem/v2-fs-find)) |
| GET | `/api/fs/read/*` | Binary file; query `location?` ([page](https://opencode.ai/v2/docs/api/filesystem/v2-fs-read)) |

No documented **write/edit** FS API (out of v1 mobile scope anyway).

### VCS (location-scoped)

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/api/vcs` | `{ location, data: { branch: { current?, default? } } }` ([page](https://opencode.ai/v2/docs/api/vcs/v2-vcs-get)) |
| GET | `/api/vcs/status` | Uncommitted files ([page](https://opencode.ai/v2/docs/api/vcs/v2-vcs-status)) |
| GET | `/api/vcs/diff` | **`mode` required:** `working` \| `branch`; optional `context`. Description: working copy vs HEAD (`working`) or default-branch merge base (`branch`). Allowed enum on the page is `working` \| `branch` (not v1 `git`). ([page](https://opencode.ai/v2/docs/api/vcs/v2-vcs-diff)) |

**No** `GET /api/session/{id}/diff` for last-turn message diffs.

### Worktree

| Method | Path | Body / response |
| --- | --- | --- |
| GET | `/api/worktree/{projectID}` | `Worktree.Directory[]` `{ directory, strategy? }` ([page](https://opencode.ai/v2/docs/api/worktree/v2-worktree-list)) |
| POST | `/api/worktree/{projectID}` | `{ strategy, directory, from?, name? }` → `{ directory }` ([page](https://opencode.ai/v2/docs/api/worktree/v2-worktree-create)) |
| DELETE | `/api/worktree/{projectID}` | [remove](https://opencode.ai/v2/docs/api/worktree/v2-worktree-remove) |
| POST | `/api/worktree/{projectID}/refresh` | [refresh](https://opencode.ai/v2/docs/api/worktree/v2-worktree-refresh) |

---

## Other index groups (mobile v1 usually out of scope)

Documented on [index](https://opencode.ai/v2/docs/api) but not expanded here
except as names: **plugin**, **generate**, **integration** (OAuth/key/command
connect), **mcp**, **credential**, **pty**, **shell** (location process shells),
**reference**, **debug** (`/api/debug/location`), **websearch**, **config**
(`GET /api/config`).

---

## Gaps to close with a live spec

These are **missing or unusable from public HTML alone**; a running server
`GET /doc` (v1 docs: [Server](https://opencode.ai/docs/server/)) or the
generated OpenAPI behind `@opencode-ai/client@beta` would help:

1. Auth: Basic vs Bearer, username, `OPENCODE_PASSWORD` vs `OPENCODE_SERVER_PASSWORD` vs `OPENCODE_TOKEN`.
2. Branded ID string formats (`ses_`, `per_`, `que_`, …).
3. Query encoding for `location`, numeric `limit`/`after`/`follow`/`continue`.
4. Full SSE `data` JSON (event `type` field vs SSE `event` name).
5. `Form.Answer` object.
6. `GET /api/session/active` value schema beyond `{}`.
7. Form vs question event names (HTTP for both is published; SSE names are not).
8. Todo HTTP (none documented) vs `todo.updated` only on the bus.
9. Archive mutation (field exists on `Session.Info.time.archived` only).
10. Session-scoped historical file diff (v1 `GET /session/{id}/diff`).

---

## Full published route list (index)

Copied from [https://opencode.ai/v2/docs/api](https://opencode.ai/v2/docs/api)
(2026-08-17). Per-page URLs follow
`https://opencode.ai/v2/docs/api/<group>/v2-<operation-with-dots-as-dashes>`.

- GET `/api/health`
- POST `/api/service/stop`
- GET `/api/server`
- GET `/api/location`
- GET `/api/agent`
- GET `/api/agent/{agentID}`
- GET `/api/plugin`
- GET `/api/session`
- POST `/api/session`
- POST `/api/session/import`
- GET `/api/session/{sessionID}/export`
- GET `/api/session/active`
- GET `/api/session/{sessionID}`
- DELETE `/api/session/{sessionID}`
- POST `/api/session/{sessionID}/fork`
- POST `/api/session/{sessionID}/agent`
- POST `/api/session/{sessionID}/model`
- POST `/api/session/{sessionID}/rename`
- POST `/api/session/{sessionID}/move`
- POST `/api/session/{sessionID}/prompt`
- POST `/api/session/{sessionID}/command`
- POST `/api/session/{sessionID}/skill`
- POST `/api/session/{sessionID}/synthetic`
- POST `/api/session/{sessionID}/shell`
- POST `/api/session/{sessionID}/compact`
- POST `/api/session/{sessionID}/wait`
- POST `/api/session/{sessionID}/revert/stage`
- POST `/api/session/{sessionID}/revert/clear`
- POST `/api/session/{sessionID}/revert/commit`
- GET `/api/session/{sessionID}/context`
- GET `/api/session/{sessionID}/inbox`
- DELETE `/api/session/{sessionID}/inbox/{inboxID}`
- POST `/api/session/{sessionID}/inbox/{inboxID}/steer`
- POST `/api/session/{sessionID}/inbox/{inboxID}/queue`
- GET `/api/session/{sessionID}/instructions/entries`
- PUT `/api/session/{sessionID}/instructions/entries/{key}`
- DELETE `/api/session/{sessionID}/instructions/entries/{key}`
- POST `/api/session/{sessionID}/generate`
- GET `/api/experimental/session/{sessionID}/log`
- POST `/api/session/{sessionID}/interrupt`
- POST `/api/session/{sessionID}/background`
- GET `/api/session/{sessionID}/message/{messageID}`
- GET `/api/session/{sessionID}/message`
- GET `/api/model`
- GET `/api/model/default`
- POST `/api/generate`
- GET `/api/provider`
- GET `/api/provider/{providerID}`
- GET `/api/integration`
- GET `/api/integration/{integrationID}`
- POST `/api/experimental/integration/wellknown`
- POST `/api/integration/{integrationID}/connect/key`
- POST `/api/integration/{integrationID}/connect/oauth`
- GET `/api/integration/{integrationID}/connect/oauth/{attemptID}`
- DELETE `/api/integration/{integrationID}/connect/oauth/{attemptID}`
- POST `/api/integration/{integrationID}/connect/oauth/{attemptID}/complete`
- POST `/api/integration/{integrationID}/connect/command`
- GET `/api/integration/{integrationID}/connect/command/{attemptID}`
- DELETE `/api/integration/{integrationID}/connect/command/{attemptID}`
- GET `/api/mcp`
- PUT `/api/mcp/{server}`
- DELETE `/api/mcp/{server}`
- POST `/api/mcp/{server}/connect`
- POST `/api/mcp/{server}/disconnect`
- GET `/api/mcp/resource`
- DELETE `/api/credential/{credentialID}`
- PATCH `/api/credential/{credentialID}`
- GET `/api/project`
- GET `/api/project/current`
- GET `/api/form/request`
- GET `/api/session/{sessionID}/form`
- POST `/api/session/{sessionID}/form`
- GET `/api/session/{sessionID}/form/{formID}`
- GET `/api/session/{sessionID}/form/{formID}/state`
- POST `/api/session/{sessionID}/form/{formID}/reply`
- POST `/api/session/{sessionID}/form/{formID}/cancel`
- GET `/api/permission/request`
- GET `/api/permission/saved`
- DELETE `/api/permission/saved/{id}`
- GET `/api/session/{sessionID}/permission`
- POST `/api/session/{sessionID}/permission`
- GET `/api/session/{sessionID}/permission/{requestID}`
- POST `/api/session/{sessionID}/permission/{requestID}/reply`
- GET `/api/fs/read/*`
- GET `/api/fs/list`
- GET `/api/fs/find`
- GET `/api/command`
- GET `/api/skill`
- GET `/api/event`
- GET `/api/pty`
- POST `/api/pty`
- GET `/api/pty/{ptyID}`
- PUT `/api/pty/{ptyID}`
- DELETE `/api/pty/{ptyID}`
- POST `/api/pty/{ptyID}/connect-token`
- GET `/api/pty/{ptyID}/connect`
- GET `/api/shell`
- POST `/api/shell`
- GET `/api/shell/{id}`
- DELETE `/api/shell/{id}`
- PATCH `/api/shell/{id}/timeout`
- GET `/api/shell/{id}/output`
- GET `/api/reference`
- GET `/api/worktree/{projectID}`
- POST `/api/worktree/{projectID}`
- DELETE `/api/worktree/{projectID}`
- POST `/api/worktree/{projectID}/refresh`
- GET `/api/vcs`
- GET `/api/vcs/status`
- GET `/api/vcs/diff`
- GET `/api/debug/location`
- DELETE `/api/debug/location`
- GET `/api/experimental/migration/v1`
- GET `/api/websearch/provider`
- POST `/api/websearch`
- GET `/api/config`
