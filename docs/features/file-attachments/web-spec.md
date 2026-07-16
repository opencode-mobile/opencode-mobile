# File Attachments — opencode Web Reference Specification

## Research Baseline

This specification records upstream opencode attachment behavior at commit
[`cb8be9ba1`](https://github.com/anomalyco/opencode/tree/cb8be9ba1217c2e7a2b93cf513eb21b41a7f5365)
(2026-07-14), inspected on 2026-07-16. Full evidence:
[`docs/research/opencode-file-upload.md`](../../research/opencode-file-upload.md).

It is a behavioral reference for a synchronized iOS and Android feature slice,
not an instruction to copy the web layout literally.

Primary upstream sources:

- [`attachments.ts`](https://github.com/anomalyco/opencode/blob/cb8be9ba1217c2e7a2b93cf513eb21b41a7f5365/packages/app/src/components/prompt-input/attachments.ts)
  for attach, paste, drop, and remove;
- [`files.ts`](https://github.com/anomalyco/opencode/blob/cb8be9ba1217c2e7a2b93cf513eb21b41a7f5365/packages/app/src/components/prompt-input/files.ts)
  and
  [`file-picker.ts`](https://github.com/anomalyco/opencode/blob/cb8be9ba1217c2e7a2b93cf513eb21b41a7f5365/packages/app/src/constants/file-picker.ts)
  for accepted types;
- [`build-request-parts.ts`](https://github.com/anomalyco/opencode/blob/cb8be9ba1217c2e7a2b93cf513eb21b41a7f5365/packages/app/src/components/prompt-input/build-request-parts.ts)
  for wire mapping;
- [`openapi.json`](https://github.com/anomalyco/opencode/blob/cb8be9ba1217c2e7a2b93cf513eb21b41a7f5365/packages/sdk/openapi.json)
  `FilePartInput` + `session.prompt_async`;
- [`prompt.ts`](https://github.com/anomalyco/opencode/blob/cb8be9ba1217c2e7a2b93cf513eb21b41a7f5365/packages/opencode/src/session/prompt.ts)
  and
  [`image.ts`](https://github.com/anomalyco/opencode/blob/cb8be9ba1217c2e7a2b93cf513eb21b41a7f5365/packages/opencode/src/image/image.ts)
  for server resolution and image limits;
- [`message-file.ts`](https://github.com/anomalyco/opencode/blob/cb8be9ba1217c2e7a2b93cf513eb21b41a7f5365/packages/session-ui/src/components/message-file.ts)
  for timeline attachment detection.

## User Goal

From Session Chat on a phone, add **Attachments** (photos, PDFs, or text-like
files from the device) to the next prompt so the opencode server and model
receive the same local-file context the desktop composer already supports.

Product language is locked in [CONTEXT.md](../../../CONTEXT.md) and
[decisions.md](decisions.md): **Attachment** (pending / sent), domain
**FilePart** for sent `data:` parts. Avoid “upload” and the legacy
**Attach context** label.

## Critical API Fact

**There is no multipart upload API.** Mobile must:

1. Read file bytes on device;
2. Encode `data:{mime};base64,{payload}`;
3. Include each file as a `FilePartInput` in the existing
   `POST /session/{sessionID}/prompt_async` JSON body next to the text part.

## Web Behavior

### Local upload vs project mention

| Concept | Composer model | Wire `url` | Mobile v1 |
| --- | --- | --- | --- |
| Local upload / paste / drop | `ImageAttachmentPart` (`type: "image"`) | `data:…;base64,…` | **In scope** |
| Project `@` file / context | `FileAttachmentPart` (`type: "file"`) | `file://…` | **Out of scope** (needs project file browsing) |

### Attach, preview, remove

1. User chooses one or more files (picker, paste, or drag-drop).
2. Client resolves MIME via `attachmentMime`:
   - keep `image/png|jpeg|gif|webp` and `application/pdf`;
   - coerce text-like content to `text/plain`;
   - reject other binaries with toast
     “Only images, PDFs, or text files can be attached here.”
3. Accepted files become pending composer attachments with id, filename, mime,
   and data URL.
4. Chips render above/near the prompt: image thumbnail or file type card;
   each chip has remove.
5. Send is enabled when text **or** attachments (or comment context) are
   present; attachment-only send is allowed on web.

### Send mapping

Each pending local attachment becomes:

```json
{
  "id": "prt…",
  "type": "file",
  "mime": "image/png",
  "filename": "photo.png",
  "url": "data:image/png;base64,…"
}
```

Text remains a `type: "text"` part. Agent/project file parts are separate and
not required for local upload.

### Server handling after accept

- `text/plain` data URLs are decoded into synthetic text context.
- Image data URLs are normalized (default max 5 MiB base64 text, 2000×2000,
  auto-resize on).
- PDF and other non-image data URLs are retained as file parts without that
  image resizer path.
- Stored/returned user messages keep `type: "file"` parts; UI treats
  `url` starting with `data:` as an attachment chip.

### Web behavior intentionally excluded from mobile v1

- Drag-and-drop and desktop project-directory picker defaults;
- `@` project file mentions, symbol ranges, review comment context;
- MCP resource attachment paths;
- Slash-command `session.command` attachment forwarding (unless mobile already
  has command send — today it uses `prompt_async` only);
- Guaranteeing parity with every web text-extension accept filter string; match
  **semantic** acceptance (images, PDF, text-like) rather than the full HTML
  `accept` attribute list unless cheap on both platforms.

## Mobile Product Specification

### Entry and presentation

- Activate the Session Chat composer `+` control labeled **Attach** /
  **Add attachment** (replace legacy **Attach context**).
- Present a platform action sheet or equivalent for:
  - Photo library (images);
  - Files / document provider (PDF + text-like documents).
- Camera is out of v1.
- Show pending Attachments as removable chips near the composer before send
  (multi-select allowed).
- After send, show domain **FilePart**s as an attachment row on the user
  message (image thumbnail or file card). Do not silently drop them from
  history. Prefer the attachment card for text-like files over inventing a
  second bubble from server synthetic text. PDF/text tap may open a system
  viewer or share sheet; no in-app PDF reader.

### Acceptance rules (client)

Accept and attach when the selected item is:

- image: `png`, `jpeg`/`jpg`, `gif`, `webp`;
- PDF: `application/pdf`;
- text-like: UTType/MIME/`text/*` or extension/heuristic equivalent to web’s
  text path, always sent as `mime: "text/plain"`.

Reject other binaries with clear copy matching web intent (images, PDFs, or
text files only).

### Encoding and send

- Encode as standard base64 data URLs (`data:{mime};base64,{payload}`).
- Extend `PromptSendOptions` / send DTO builders so `prompt_async` can include
  zero or more file parts plus the text part.
- Allow send when text is non-empty **or** at least one pending attachment
  exists (match web).
- Clear text and pending Attachments after HTTP **204** accept of
  `prompt_async` (same moment the composer clears text today). Do **not**
  automatically restore chips when a later `Session.Event.Error` arrives
  (image normalize / size). Surface that error in Session Chat. Non-2xx HTTP
  failures keep text and pending Attachments for retry.
- Do not invent a separate upload progress API; the HTTP `prompt_async` call
  is the transfer. Show in-flight send state on the composer as today.
- Stay on V1 `parts` / `FilePartInput`. Do not switch to V2
  `POST /api/session/{sessionID}/prompt` (`prompt.files[].uri`) in this slice.

### Size and memory

- Prefer reading reasonably sized files; surface failure if encoding or HTTP
  send fails (OOM, non-2xx).
- Document the server default **5 MiB base64** image cap. Mobile may add a
  preflight warning or client resize later; v1 may rely on server normalize /
  async `Session.Event.Error` for oversized images after 204.
- Avoid holding many large data URLs longer than needed; drop bytes from
  composer state after HTTP accept.

### Domain model

- **Attachment** (pending): local id, filename, mime, kind
  (`image` | `pdf` | `text`), and data URL or platform-safe byte handle until
  encoded.
- **FilePart** (sent): id, mime, filename, url/kind for timeline rendering of
  `data:` attachments.

Do not expose raw OpenAPI names in feature UI copy. Avoid “upload.”

### State model and actions

Composer / Session Chat needs:

- pending attachments list;
- attach / remove / clear;
- unsupported-type error;
- send that includes attachments;
- timeline projection of file parts on user messages.

Explicit actions: `attachFiles`, `removeAttachment`, `clearAttachments`, and
the existing send path extended to carry attachments.

### Safety and accessibility

- Request Photos / Files permissions only when the user invokes attach.
- Accessibility labels for attach, each chip, remove, and file-part timeline
  items (filename + type).
- Do not execute attached content; it is opaque context for the server/model.
- On cellular or constrained networks, large base64 JSON bodies may be slow;
  keep failure recoverable without losing typed text when send fails (match
  current prompt-send restore behavior).

## Current Mobile Gap

- Composer `+` is a visual no-op
  ([session-chat decision](../session-chat/decisions.md#keep-attachment-as-visual-affordance-only)).
- Android/iOS send builders emit only a text part.
- Domain `MessagePart` is `text` | `tool` only; file parts from history are
  dropped or ignored in adapters today.
- No photo/document picker integration for Session Chat.

## Required Implementation Slices

1. **Research / contract** (this document + research note) — done as planning.
2. **Domain + DTO:** `MessagePart.File`, composer attachment model,
   `prompt_async` multi-part request builder, adapter mapping for inbound
   `type: "file"` with `data:` URLs.
3. **Design system:** attachment chip / file-part primitives if reusable;
   otherwise extend Session composer components via the design-system workflow.
4. **Composer UX:** wire `+` to pickers, pending chips, remove, send gating.
5. **Timeline UX:** render image and non-image data-URL attachments on user
   messages.
6. **Validation:** MIME/reject tests, encode/send tests, history round-trip
   tests, simulator/device evidence on both platforms.

## Acceptance Criteria

- User can attach at least one image, one PDF, and one text-like file from the
  device and see them as removable chips before send.
- Unsupported types are rejected with clear copy; composer text is preserved.
- Successful send issues `prompt_async` with text + `FilePartInput` data URL
  parts (attachment-only send allowed).
- Pending Attachments clear after HTTP 204; non-2xx keeps text and Attachments
  for retry; post-204 session errors surface without restoring chips.
- User messages that include data-URL file parts show attachment UI in the
  timeline after refresh/SSE.
- iOS and Android match behavior and copy semantics; intentional visual
  differences are recorded in `parity-gaps.md`.
- No project `file://` mention browser is required for this slice.

## Out of Scope

- Multipart upload endpoints or app-managed object storage;
- Project file tree, `@` mentions, or server-path attachments;
- MCP resource attach;
- Drag-and-drop;
- Push/background upload;
- Full mobile IDE / arbitrary binary attach;
- Client-side image editor beyond optional future resize for the 5 MiB cap.
