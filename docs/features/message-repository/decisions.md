# Message Repository — Decisions

## Repository boundary

`MessageRepository` covers message read/send routes only. `SessionRepository` remains session lifecycle.

## Slice delivery

Ship repository **and** wire Session Chat (history + async send), matching Session Repository + home list.

## Post-send UX

Optimistic user bubble, then full `listMessages` reload after `prompt_async` succeeds. On failure, show error and remove the optimistic row.

While `isSending`, `MessageSync`-driven reloads in Session Chat use a **quiet
refresh** path in the Screen Model: they merge server messages with any pending
optimistic row (until the server returns matching user text) and do not clear
`isSending` or the error banner. Explicit load, pull-to-refresh, and the
post-send reload still replace the timeline and clear the sending flag.

Timeline application may coalesce callback-free live frames, but must not
discard explicit refresh or post-send completion work. Android keeps those
required applications in FIFO order while retaining only the newest
callback-free live frame; iOS tracks and lifecycle-cancels its apply task.

## Provider errors on assistant messages

When `parts` are empty but `info.error` is present, `MessageDomainAdapter` maps display text from `error.data.message`, then `error.data.responseBody`, then `error.name`. Session Chat shows that string in the assistant bubble instead of the `…` placeholder.

## Prompt payload

Prompt payloads keep text parts unchanged and may include `model:
{ providerID, modelID }` plus a non-default top-level `variant` from the
Session Chat Model Selection slice.

## Domain vs UI

Adapter exposes `user`, `assistant`, `command`, `tool`, and `unknown` roles. Session Chat renders user and assistant; other roles use a neutral fallback row.

## Sync vs async send

- `sendPromptAsync` — normal mobile Send (returns 204).
- `sendMessage` — sync `POST /message`; exposed on repository for parity; Session Chat does not call it in this slice.

## Transport

Hand-written Ktor / URLSession clients aligned with `api/opencode-messages.openapi.yaml`, same pattern as Session Repository.

## DI

- Android: `MessageModule` (Hilt).
- iOS: Factory registrations in `AppContainer`.

## REST refresh and watchdog

Pull-to-refresh and reload when Session Chat appears. `MessageSync` owns the
foreground changed-message watchdog poll (see
[Foreground Event Stream](../foreground-event-stream/decisions.md)), not the
repository transport adapter.

Both platforms expose `server.connected` to the domain stream. `MessageSync`
debounces the synchronization requested by that server event; a synthetic
`stream.reconnected` cancels the pending debounce and reloads immediately.

## SSE message part updates

`MessageSync` applies live patches through `InMemoryMessageStore`.
`message.part.updated` is an ordered replace-by-ID operation. The store keeps
server part IDs privately, preserves first-seen order for rendering, and
replaces an existing part in place when the same part ID arrives again.

`message.part.delta` with `field=text` appends to an existing text part and
marks it streaming. Missing or non-text fields are ignored. Tool updates replace
the whole rendered tool part from the latest state object; tool state fields are
not merged incrementally.

REST mapping retains stable server part IDs inside the domain part model, so a
part update targeting a REST-seeded message can replace that part locally while
preserving every sibling and following delta. Legacy decoded parts without an
ID keep the full-reload fallback rather than inventing unstable local identity.

Polling pauses while text is streaming. If no message events arrive for one
poll interval while text is still marked streaming, the watchdog REST-polls
anyway and applies the snapshot only when it is ahead of (or equal to and more
finalized than) the local stream. That recovers a hung socket that still looks
`connected` without rewinding a healthy mid-stream pause. A final part update
clears the streaming flag; `session.idle` reloads the authoritative REST
snapshot so a missed final part event cannot leave a message streaming
indefinitely. A failed idle reload clears the local streaming flag and resumes
watchdog polling. REST results are applied only when the store revision is
unchanged since the request started, so an in-flight poll cannot overwrite a
newer SSE delta.

`message.updated` can arrive before visible parts. The store may record
that message slot internally, but Session Chat should not render a brand-new
empty message row. The row becomes visible when a renderable text/tool part or
error fallback exists. Existing visible rows may stay visible from the last REST
snapshot while incremental parts catch up.

Message streams subscribe and buffer foreground events before the first REST
load. They should not emit an initial empty list while that load is still in
flight, and deltas received during it are applied after the snapshot is seeded.
They also suppress duplicate unchanged timeline emissions from SSE events, so
placeholder-only events do not repaint or blank the chat.

Reasoning is a first-class part, not folded into assistant text. Session Chat
should render thinking to match TUI v2. A collapsible thinking component can
sit on the existing repository contract.

## Display preparation and pacing

`DisplayMessagePipeline` is the only path from synchronized domain messages to
timeline-ready display messages. Streaming snapshots use a short quiet window
with a fixed maximum batch age, so dense token bursts reduce repaint pressure
without progressively slowing long replies.

Large histories publish a usable format-first timeline scaffold before TextMate
color enrichment. Enrichment proceeds newest-first as cancellable follow-up
work; a new live snapshot cancels stale enrichment rather than waiting behind
it. Streaming attaches formatted scaffolds when markdown is detected (OPE-92);
TextMate colors (or deferred semantic catch-up when TextMate is off) arrive on
Final / history enrichment. Large finalized Android annotated strings are
built off the UI thread.
