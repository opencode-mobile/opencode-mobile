# TUI session logic — opencode vs opencode2

Date: 2026-08-18

Source: local `opencode` checkout (`packages/tui`, `packages/cli`, `packages/server`, `packages/sdk`, `packages/opencode`).

This is how the official TUI drives an **open session**: which HTTP APIs it
calls, how those map into UI state, and how SSE is applied. It is not a
mobile implementation plan.

## Shared TUI, two servers

Both products run the **same** `packages/tui` app (`@opencode-ai/tui`).

| | `opencode` (classic) | `opencode2` (`packages/cli`) |
| --- | --- | --- |
| Launch | `packages/opencode/src/cli/cmd/tui.ts` | `packages/cli/src/tui.ts` → `runTui()` |
| Server | Dual-mount HttpApi: unprefixed **and** `/api/*` | `packages/server` via protocol `makeDefaultApi` — **`/api/*` only** |
| Health | `GET /global/health` | `GET /api/health` (`daemon.ts`) |
| Session UI store | `SyncProvider` (`sync.tsx`) | same — still `useSync()` |
| v2 store | `DataProvider` (`data.tsx`) — autocomplete only | same |
| Fetch shim | none (worker RPC or real HTTP) | `gracefulFetch`: 404 on `/config`, `/provider`, `/agent`, `/config/providers` → empty JSON |

There is **no SDK translator** that rewrites `/session/*` into `/api/session/*`.
`createOpencodeClient` only copies `x-opencode-directory` / workspace onto
query params (`packages/sdk/js/src/v2/client.ts`).

So: **session logic is v1-shaped in both TUIs.** `opencode2` does not migrate
the session screen; it only stubs a few catalog 404s.

```text
                    GET /global/event   (classic server)
                    GET /api/event      (opencode2 server — TUI does not subscribe)
                              │
                              ▼
                    GlobalEvent { directory, workspace, payload }
                              │
                    useEvent() drops payload.type === "sync"
                    unwraps payload → Event { type, properties }
                              │
              ┌───────────────┴───────────────┐
              ▼                               ▼
     SyncProvider (v1 names)          DataProvider (session.next.*)
     session UI reads this            autocomplete / unused timeline
```

---

## 1. TUI `opencode` — session REST

SDK is `@opencode-ai/sdk/v2` `OpencodeClient`, but **session methods hit
unprefixed paths**.

### Open session

`routes/session/index.tsx` on `route.sessionID`:

| SDK | HTTP | Translated to |
| --- | --- | --- |
| `session.get({ sessionID })` | `GET /session/{id}` | session info (title, revert, compacting, workspace). If workspace changed → `sync.bootstrap` |
| `sync.session.sync(sessionID)` | four calls below | first full hydrate; later SSE wins via `touchMessage` / `touchPart` |

`sync.session.sync` (once per session):

| SDK | HTTP | Store | UI |
| --- | --- | --- | --- |
| `session.get` | `GET /session/{id}` | `store.session[]` | title, revert banner, compacting |
| `session.messages({ limit: 100 })` | `GET /session/{id}/message?limit=100` | `store.message[id]` + `store.part[messageID]` | timeline (`role` + parts) |
| `session.todo` | `GET /session/{id}/todo` | `store.todo[id]` | todo list |
| `session.diff` | `GET /session/{id}/diff` | `store.session_diff[id]` | diffs |

App bootstrap (not per session) also:

| SDK | HTTP | Store |
| --- | --- | --- |
| `session.list` | `GET /session` | home / picker list |
| `session.status` | `GET /session/status` | `store.session_status` (busy / idle / retry) |

Hydration rule: ids touched by SSE while REST is in flight are **kept**. REST
does not overwrite a newer live text/reasoning part.

### Send / mutate (session screen)

| Action | SDK | HTTP | What appears on screen |
| --- | --- | --- | --- |
| Prompt | `session.prompt({ sessionID, parts, agent, model, variant })` | `POST /session/{id}/message` | **nothing local**. Server publishes `message.updated` + `message.part.updated` |
| Slash command | `session.command` | `POST /session/{id}/command` | same via SSE |
| Shell mode | `session.shell` | `POST /session/{id}/shell` | same via SSE |
| Abort | `session.abort` | `POST /session/{id}/abort` | later `session.status` / message events |
| Rename | `session.update` | `PATCH /session/{id}` | `session.updated` or next get |
| Delete | `session.delete` | `DELETE /session/{id}` | leave session |
| Fork | `session.fork` | `POST /session/{id}/fork` | navigate to new id |
| Revert / unrevert | `session.revert` / `unrevert` | `POST /session/{id}/revert` / `unrevert` | session info `revert` via `session.updated` |
| Compact | `session.summarize` | `POST /session/{id}/summarize` | compacting on session info + later messages |
| Permission reply | `permission.reply` | `POST /permission/{requestID}/reply` | request removed on `permission.replied` |
| Question reply / reject | `question.reply` / `reject` | `POST /question/{requestID}/reply` or `/reject` | dock clears on replied/rejected |

`session.promptAsync` (`POST /session/{id}/prompt_async`) exists on the SDK.
The session composer does **not** call it; it uses `session.prompt` →
`POST /session/{id}/message`.

---

## 2. TUI `opencode` — SSE

### Transport

| Mode | How events arrive |
| --- | --- |
| Remote / attached | `sdk.global.event()` → `GET /global/event`, abort + backoff 1s–30s, 16ms Solid `batch` (`sdk.tsx`) |
| Embedded | worker RPC `global.event` (`createEventSource` in `cli/cmd/tui.ts`) |

Envelope:

```ts
GlobalEvent = {
  directory, workspace?,
  payload: { id, type, properties }
}
```

`useEvent()`:

1. Ignore `payload.type === "sync"`.
2. Pass `payload` as `Event` plus `{ directory, workspace }`.

Neither path calls `GET /api/event`.

### Event → store → session UI

`sync.tsx` `event.subscribe` switch. No `default` — unlisted types are dropped.

| SSE `type` | Payload used | Store write | Session UI |
| --- | --- | --- | --- |
| `message.updated` | `properties.info` | upsert `store.message[sessionID]` (cap 100) | new / updated user or assistant row |
| `message.removed` | `messageID` | remove message | row gone |
| `message.part.updated` | `properties.part` | upsert `store.part[messageID]` | text, tool, reasoning, files |
| `message.part.delta` | `partID`, `field`, `delta` | append string on that part field | streaming text / reasoning |
| `message.part.removed` | `partID` | remove part | part gone |
| `session.status` | `status` | `store.session_status[id]` | working / idle / retry chrome |
| `session.updated` | `properties.info` | upsert session | title, revert, compacting |
| `session.deleted` | `info.id` | remove from list | leave if it was open (`app.tsx`) |
| `session.diff` | `diff` | `store.session_diff[id]` | diffs |
| `session.next.moved` | `location` | patch directory / workspace | path chrome |
| `todo.updated` | `todos[]` | replace `store.todo[id]` | todos |
| `permission.asked` | request | insert permission | permission dock (auto-reply `"once"` if permission mode is auto) |
| `permission.replied` | `requestID` | remove | dock clears |
| `question.asked` | request | insert | question dock |
| `question.replied` / `.rejected` | `requestID` | remove | dock clears |
| `session.error` | — | **not in Sync** | toast in `app.tsx` |
| `server.instance.disposed` | — | re-bootstrap | full refresh |

Session-route extras (same `sessionID` only):

| Event | Translation |
| --- | --- |
| `message.part.updated` completed `plan_enter` / `plan_exit` | local agent plan/build |
| `session.status` `retry` + action | Go upsell dialog |

### User message path (why no inbox event)

```text
POST /session/{id}/message
        │
        ▼
Server createUserMessage
        │
        ├─ publish message.updated   { info: { id, role: "user", sessionID, … } }
        └─ publish message.part.updated  { part: { type: "text", text, … } }
        │
        ▼
Sync upserts message + part
        │
        ▼
session.tsx  messages() = sync.data.message[id]
             parts     = sync.data.part[message.id]
```

`session.inbox.enqueued` is not in this TUI. A queued/remote prompt shows up
the same way: when the **v1** bus emits `message.updated`.

Ignored on the session timeline (among others): `session.idle`,
`session.created`, `session.compacted`, `permission.v2.*`, `question.v2.*`,
`session.inbox.*`, almost all `session.next.*` except `moved`.

---

## 3. TUI `opencode2` — what actually runs

Same session UI. Same `SyncProvider` calls. Same `GET /global/event` subscribe.

`opencode2` server **does not mount** `/session` or `/global/event`. It mounts
protocol routes such as:

| v2 HTTP | Purpose |
| --- | --- |
| `GET /api/health` | daemon health |
| `GET /api/event` | live bus (TUI session does not use) |
| `GET /api/session` | list |
| `GET /api/session/{id}` | get |
| `GET /api/session/{id}/message` | history |
| `POST /api/session/{id}/prompt` | admit input + run |
| `POST /api/session/{id}/interrupt` | abort |
| `GET /api/session/active` | busy map |

`packages/cli/src/tui.ts` `gracefulFetch` **does not** map those. It only
fakes empty bodies for:

- `/config/providers`
- `/provider`
- `/agent`
- `/config`

So on a fully migrated v2-only server:

| TUI session call | Happens |
| --- | --- |
| `GET /session/{id}` | 404 |
| `GET /session/{id}/message` | 404 |
| `POST /session/{id}/message` | 404 |
| `GET /global/event` | 404 / no stream |
| Catalog stubs | empty lists, TUI keeps booting |

**No v1→v2 translation for session.** Session Chat cannot work against a
v2-only server until the route reads `DataProvider` and the SSE client
subscribes to `/api/event`.

---

## 4. Intended v2 translation (DataProvider — unused by session UI)

`data.tsx` already knows the v2 mapping. The session route never calls
`data.session.message.refresh` and never renders `store.session.message`.

### REST it would use

| Method | HTTP | Store |
| --- | --- | --- |
| `v2.session.get` | `GET /api/session/{id}` | `store.session.info[id]` |
| `v2.session.messages` | `GET /api/session/{id}/message` | `store.session.message[id]` (`SessionMessage[]`) |
| `v2.session.permission.list` | `GET /api/session/{id}/permission` | permissions |
| `v2.session.question.list` | `GET /api/session/{id}/question` | questions |

Subscribe remap (same `/global/event` bus today, **not** `/api/event`):

```ts
handleEvent({
  ...event,
  data: event.properties,   // v1 envelope → v2 `data`
  location: { directory, workspaceID },
} as V2Event)
```

A v1 `message.updated` frame does nothing here. Handlers only run if `type`
is already `session.next.*`.

### SSE `session.next.*` → v2 message model

| Incoming type | Translated row / mutation |
| --- | --- |
| `session.next.prompted` | prepend `{ type: "user", text, files, agents, id: messageID }` |
| `session.next.prompt.admitted` | **no-op** (queue ack) |
| `session.next.step.started` | prepend assistant (or complete previous) |
| `session.next.step.ended` / `.failed` | complete / error assistant |
| `session.next.text.started` / `.delta` / `.ended` | assistant text |
| `session.next.reasoning.started` / `.delta` / `.ended` | reasoning |
| `session.next.tool.input.*` / `.called` / `.progress` / `.success` / `.failed` | tool card |
| `session.next.shell.started` / `.ended` | shell row |
| `session.next.compaction.ended` | compaction row |
| `session.next.agent.switched` / `.model.switched` | switch rows |
| `session.next.context.updated` | system text |
| `session.next.synthetic` | synthetic |
| `session.next.retried` / `.compaction.started` / `.delta` | no-op |
| `session.next.revert.*` | unhandled |
| `session.inbox.*` | unhandled (not in this schema) |

User-row split on v2: **admitted / inbox.enqueued = queued**; **prompted =
visible user message**. Desktop’s reducer uses the same idea as
`session.input.admitted` → stash, `session.input.promoted` → append.

---

## 5. Side-by-side (open session)

| Concern | TUI opencode (live) | TUI opencode2 (today) | DataProvider (not wired) |
| --- | --- | --- | --- |
| History | `GET /session/{id}/message` | same call, 404 on v2-only | `GET /api/session/{id}/message` |
| Send | `POST /session/{id}/message` | same call, 404 | `POST /api/session/{id}/prompt` |
| SSE | `GET /global/event` | same subscribe, no v1 bus | would need `GET /api/event` |
| User row | `message.updated` + `part.updated` | same, never arrives | `session.next.prompted` |
| Status | `session.status` | same | not applied in DataProvider |
| Diffs | `session.diff` event + REST | same | not in DataProvider |
| Permissions | `permission.asked` v1 | same | `v2.session.permission.list` REST only unless v2 events added |

Until `routes/session/index.tsx` uses `useData()` and the client subscribes to
`/api/event`, **the official session screen contract is v1 `message.*` +
`session.status`**, whether you launched `opencode` or `opencode2`.

---

## 6. Correction — `origin/beta` TUI v2 + mobile

Section 5 describes `origin/dev`. The working **opencode2** TUI is on
`origin/beta`: `useData()` + `stream-v2.transport.ts` +
`client.event.subscribe()` → `GET /api/event`.

| Concern | TUI v1 (`dev`) | TUI v2 (`beta` / opencode2) | Mobile |
| --- | --- | --- | --- |
| SSE | `GET /global/event` | `GET /api/event` | v1 `/global/event` or v2 `/api/event` |
| Send | `POST /session/{id}/message` | `POST /api/session/{id}/prompt` | v1 `prompt_async` or v2 `/api/session/{id}/prompt` |
| User row | `message.updated` + `part.updated` | `inbox.enqueued` stash, `inbox.delivered` print | project `inbox.enqueued` / `.delivered` / `session.next.prompted` → `message.*` |
| Assistant | `message.part.delta` | `session.text.*` / `step.*` / `tool.*` / `session.reasoning.*` | project `session.text.*` / `session.reasoning.*` / `session.next.*` → distinct text vs reasoning parts |
| Status | `session.status` | `session.execution.*` | project to `session.status` / `session.idle` |
| v2-only server | live SSE 404 | works | works |

Mobile is TUI v1’s store model plus a v2 bus adapter. It is the only of the
three that speaks both servers.
