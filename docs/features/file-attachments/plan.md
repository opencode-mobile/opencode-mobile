# File Attachments

Linear: [OPE-55 — Attachments](https://linear.app/opencode-mobile/issue/OPE-55/attachments)

## User Goal

Attach device photos, PDFs, and text-like files to a Session Chat prompt so the
opencode server receives the same local-file context desktop already supports.

Product language: **Attachment** (pending in the composer, sent as
**FilePart**). See [CONTEXT.md](../../../CONTEXT.md) and
[decisions.md](decisions.md).

## In Scope

- Wire the composer attach affordance (label **Attach** / **Add attachment**)
  to Photos library and Files/document pickers.
- Accept images (`png` / `jpeg` / `gif` / `webp`), PDF, and text-like files;
  allow multiple Attachments per prompt.
- Encode as base64 data URLs and send as wire `type: "file"` parts on
  `POST /session/{sessionID}/prompt_async`.
- Show removable pending chips before send; allow attachment-only send.
- Map inbound `data:` file parts to domain **FilePart** and render attachment
  rows on user messages (image thumbnail or file card).
- Design-system attachment chip and message-attachment primitives before
  feature composition.
- Keep iOS and Android behavior synchronized.

## Out Of Scope

- Multipart upload APIs or separate upload sessions.
- Project `@` mentions / `file://` server-path attachments / file browsing.
- Camera capture.
- Drag-and-drop, MCP resource attach, slash-command attachment forwarding.
- Client image editor / hard client resize; in-app PDF reader.
- Restoring pending chips after post-204 `Session.Event.Error`.

Upstream evidence and mobile product rules:
[web-spec.md](web-spec.md). Research note:
[opencode-file-upload.md](../../research/opencode-file-upload.md).

## Server APIs Involved

- `POST /session/{sessionID}/prompt_async` — extend body `parts` with
  `FilePartInput` (`type`, `mime`, `url`, optional `filename` / `id`).
  Returns **204** and forks work.
- `GET /session/{sessionID}/message` and existing SSE message/part events —
  consume `type: "file"` parts with `data:` URLs.
- `Session.Event.Error` — surface post-accept failures (for example image size).

No new upload endpoint.

## Domain Objects

- **Attachment** — pending composer item (id, filename, mime, kind, bytes/data
  URL).
- **FilePart** — sent/history message part for a data-URL attachment.
- Extended `PromptSendOptions` — text plus attachments (and existing model /
  variant / mode fields).

## Screen Model

Session Chat composer owns pending Attachments and attach/remove/send actions.
Timeline owns attachment-row rendering on user messages. Facade remains thin.

## UI States

- No attachments: current composer.
- Pending attachments: chips + enabled send even if text empty.
- Model without image or PDF input: Attach disabled.
- Model with partial input support: Attach enabled; Photos hidden without image
  input; Files kept when Attach is enabled; unsupported kinds rejected with
  clear copy.
- Unsupported type: non-fatal error; selection rejected.
- Sending: existing in-flight send state.
- HTTP failure: restore text and pending attachments.
- HTTP 204: clear text and pending attachments; later session errors surface
  without restoring chips.
- Timeline: attachment row with image thumbnail or file card; PDF/text may open
  system viewer/share on tap.

## Error States

- Unsupported MIME/type.
- Read/encode failure on device.
- Transport / non-2xx on `prompt_async` (preserve text + attachments).
- Post-204 `Session.Event.Error` (including image normalize / size).

## Permission Behavior

- Photos and Files access requested only when the user taps attach.
- No opencode server permission prompt is specific to local data-URL attach.

## Design-System Components

- New or extended reusable attachment chip and message-attachment primitives
  through the design-system component workflow.
- Feature-local UI only if a reusable primitive is unsuitable; document why in
  `decisions.md`.

## Native Navigation Entry Points

- Session Chat composer attach control (**Attach** / **Add attachment**).

## Expected Platform Logs

- Attach accepted/rejected (mime, size if known, count).
- `prompt_async` send including file part count (not full base64).
- Inbound FilePart mapped for timeline display.
- Session error after async prompt when relevant.
