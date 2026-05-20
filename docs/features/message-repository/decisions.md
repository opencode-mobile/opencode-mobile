# Message Repository — Decisions

## Repository boundary

`MessageRepository` covers message read/send routes only. `SessionRepository` remains session lifecycle.

## Slice delivery

Ship repository **and** wire Session Chat (history + async send), matching Session Repository + home list.

## Post-send UX

Optimistic user bubble, then full `listMessages` reload after `prompt_async` succeeds. On failure, show error and remove the optimistic row.

## Prompt payload

Text-only `parts: [{ type: "text", text }]` for send routes. Composer model/mode/Thinking Effort chips stay mock/local until a follow-up slice.

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

Pull-to-refresh and reload when Session Chat appears. No polling.
