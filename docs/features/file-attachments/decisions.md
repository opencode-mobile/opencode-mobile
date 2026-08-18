# File Attachments — Decisions

Resolved in the 2026-07-16 grilling session (all recommendations accepted).

## No multipart upload; use prompt data URLs

- Date: 2026-07-16
- Decision: Mobile attaches files by embedding `data:` URLs in
  `FilePartInput` on `prompt_async`, matching upstream. Do not design an
  app-managed upload/relay endpoint unless the server adds one.
- Reason: OpenAPI and web clients have no separate upload API for composer
  attachments; the prompt body is the transfer.
- Revisit when: server adds a first-class upload API that mobile should adopt.
- ADR: not warranted — follows upstream contract rather than a mobile-only
  hard-to-reverse trade-off.

## Product term is Attachment

- Date: 2026-07-16
- Decision: Canonical glossary term is **Attachment** (pending in composer,
  sent as **FilePart**). Docs directory stays `file-attachments`.
- Reason: “Upload” implies a missing API; “Attach context” implies project
  context; wire `type: "file"` is an adapter detail.
- Revisit when: project `@` / `file://` context gets its own feature language.

## Local device files only in this slice

- Date: 2026-07-16
- Decision: This slice is device photos/documents encoded as data URLs. Project
  `@` mentions, `file://` server-path attachments, and camera capture were
  not built here.
- Reason: Server-path attach needs project browsing. Camera was not required
  for the first web-parity pass.
- Revisit when: project-context or camera follow-ups are planned.

## Match web acceptance semantics

- Date: 2026-07-16
- Decision: Accept images (png/jpeg/gif/webp), PDF, and text-like files
  (sent as `text/plain`). Reject other binaries with clear copy. Allow
  multiple attachments per prompt.
- Reason: Mirrors `attachmentMime` / unsupported-attachment toast in web.
- Revisit when: upstream expands composer attach types.

## Attachment-only send allowed

- Date: 2026-07-16
- Decision: Send is enabled when there is non-empty text **or** at least one
  pending attachment.
- Reason: Web empty-check allows attachment-only prompts.
- Revisit when: product wants to require accompanying text on mobile.

## Clear composer on HTTP 204; do not restore chips on Session.Event.Error

- Date: 2026-07-16
- Decision: Clear text and pending attachments on `prompt_async` **204**,
  matching today’s text-send clear. Surface later `Session.Event.Error`
  (including image size failures) in Session Chat without automatically
  restoring attachment chips. Failed non-2xx HTTP keeps text and pending
  attachments for retry.
- Reason: Upstream forks work after 204; restoring large base64 after async
  failure is messy. HTTP failure still deserves draft restore.
- Revisit when: mobile adds a dedicated async send-result model.

## No hard client image resize in this slice

- Date: 2026-07-16
- Decision: Rely on server normalize (default 5 MiB base64 / 2000×2000) and
  async errors. Optional soft size warning is allowed; client editor/resize
  is out of scope.
- Reason: Keeps the slice aligned with web’s client path.
- Revisit when: field reports OOMs or oversized sends are common.

## Timeline shows attachment rows on user messages

- Date: 2026-07-16
- Decision: Render sent FileParts as an attachment row inside the user
  message (image thumbnail or file card), not as Tool Call Cards and not
  flattened into `text`. Prefer the chip/card for text-like attachments over
  inventing a second bubble from synthetic server text. Inline image preview
  in timeline; PDF/text tap may open a system viewer/share sheet; no in-app
  PDF reader.
- Reason: Matches web’s data-URL attachment presentation without expanding
  mobile IDE scope.
- Revisit when: richer preview is requested.

## Live inbox image without bytes shows a file-card placeholder

- Date: 2026-08-17
- Decision: Keep a domain **FilePart** when live inbox omits `url`/`uri` but
  still has image `mime` or an image filename. Render the existing message
  attachment file card (filename + IMAGE, no thumbnail) until REST supplies
  the data URL and the local thumbnailer fills the preview.
- Reason: SSE drops large file URIs, so dropping the part made the watching
  client show nothing until a slow REST reload. The file card is already the
  documented fallback when preview bytes are missing.
- Revisit when: the server sends a thumbnail or a fetchable file URL instead
  of embedding the full data URL.

## Hide synthetic and ignored text parts in Session Chat

- Date: 2026-08-06
- Decision: Map wire `synthetic` / `ignored` onto domain `TextPart`, keep the
  parts for store/context fidelity, and exclude them from `displayText` and
  Session Chat timeline text blocks. Match desktop
  `UserMessageDisplay` (`!synthetic`) and prompt restore (`!synthetic &&
  !ignored`).
- Reason: For text-file attachments the server injects synthetic text (Read
  tool label + decoded file body) beside the `file` part. Showing that raw
  body duplicates the attachment row and diverges from desktop.
- Revisit when: mobile adds review-comment extraction from synthetic metadata
  (desktop `MessageComment.fromPart`).
- ADR: not warranted — follows upstream UI contract.

## Gate Attach on model input capabilities

- Date: 2026-07-17
- Decision: Read `GET /provider` model `capabilities.input.image` and
  `capabilities.input.pdf`. Disable Attach when neither is true (text-only
  models). When Attach is enabled, show Photos only for image input and keep
  Files for any attach-capable model; reject PDFs when `input.pdf` is false.
  Switching models strips pending image/PDF chips the new model cannot accept.
  Text attachments remain allowed whenever Attach is enabled.
- Reason: Upstream enforces unsupported media at send time via
  `capabilities.input[modality]`; mobile should prevent the dead-end before
  send. Catalog `capabilities.attachment` alone can diverge from modalities.
  When the provider omits `capabilities.input`, treat image/pdf as supported so
  older catalogs and local fixtures keep Attach available.
- Revisit when: audio/video attach enters mobile scope or upstream changes the
  provider capability contract.

## Composer label and pickers

- Date: 2026-07-16
- Decision: Rename the affordance from **Attach context** to **Attach** /
  **Add attachment**. Entry is Photos library + Files/document picker via
  platform action sheet (or equivalent). Request Photos/Files permission only
  on attach. Pending state lives in the Session Chat composer Screen Model;
  send extends Message Repository `PromptSendOptions`. Optimistic send, if
  already used for text, includes pending attachments — do not invent a new
  optimism model.
- Reason: Aligns copy with device attachments and keeps architecture consistent
  with Session Chat regions.
- Revisit when: camera or project-context entry points are added.

## Design-system primitives before feature glue

- Date: 2026-07-16
- Decision: Add reusable attachment chip and message-attachment primitives
  through the design-system workflow before composing Session Chat.
- Reason: Component-first feature workflow.
- Revisit when: audit shows an existing primitive already fits.
