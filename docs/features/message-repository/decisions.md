# Message Repository — Decisions

## Repository boundary

`MessageRepository` covers message read/send routes only. `SessionRepository` remains session lifecycle.

## Slice delivery

Ship repository **and** wire Session Chat (history + async send), matching Session Repository + home list.

## Post-send UX

Optimistic user bubble, then full `listMessages` reload after `prompt_async` succeeds. On failure, show error and remove the optimistic row.

While `isSending`, Live Store-driven reloads in Session Chat use a **quiet refresh** path in the Screen Model: they merge server messages with any pending optimistic row (until the server returns matching user text) and do not clear `isSending` or the error banner. Explicit load, pull-to-refresh, and the post-send reload still replace the timeline and clear the sending flag.

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

## Refresh without SSE

Pull-to-refresh and reload when Session Chat appears. The foreground changed-message watchdog poll is owned by **MessageLiveStore** (see [Foreground Event Stream](../foreground-event-stream/decisions.md)), not Message Repository.

## SSE message part updates

`MessageLiveStore` treats `message.part.updated` as an ordered replace-by-ID
operation. The store keeps server part IDs privately, preserves first-seen part
order for rendering, and replaces an existing part in place when the same part ID
arrives again.

Text `delta` updates append to the existing text part and mark that part as
streaming. Tool updates replace the whole rendered tool part from the latest
state object; tool state fields are not merged incrementally.

`message.updated` can arrive before visible parts. The Live Store may record
that message slot internally, but Session Chat should not render a brand-new
empty message row. The row becomes visible when a renderable text/tool part or
error fallback exists. Existing visible rows may stay visible from the last REST
snapshot while incremental parts catch up.

Live Store streams should not emit an initial empty list while the first REST
load is still in flight. They should also suppress duplicate unchanged timeline
emissions from SSE events, so placeholder-only events do not repaint or blank the
chat.

Reasoning parts are hidden from normal chat text for v1. A future collapsible
reasoning component can surface them without changing the message repository
contract.
