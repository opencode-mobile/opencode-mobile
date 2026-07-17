# File Attachments — Progress

## Shared planning

- [x] Upstream research (`docs/research/opencode-file-upload.md`)
- [x] Web reference + mobile product spec (`web-spec.md`)
- [x] Feature plan (`plan.md`)
- [x] Grilling decisions locked (`decisions.md`, `CONTEXT.md` glossary)
- [x] Linear parent issue [OPE-55](https://linear.app/opencode-mobile/issue/OPE-55/attachments)
  (single-ticket slice)
- [x] Design-system attachment chip / message-attachment primitives
- [x] Domain FilePart + Attachment model + send builder on both platforms
- [x] Composer pickers + pending chips + Add attachment label
- [x] Timeline attachment-row rendering
- [x] Session.Event.Error surfacing for post-204 failures
- [x] Cross-platform automated validation

## Design-system dependencies

- Complete: shared `OpenCodeAttachmentChip` and `OpenCodeMessageAttachment`
  contracts, native implementations, and Component Gallery review surfaces.

## iOS implementation

- Complete: Photos and Files entry points support multiple scoped selections;
  accepted content becomes removable pending chips and text/file prompt parts.
- FileParts from history and live updates render on outgoing messages, including
  attachment-only prompts.
- Failed HTTP sends restore the submitted draft and attachments without
  overwriting newer edits; accepted sends remain cleared when a later session
  error is surfaced.

## Android implementation

- Complete: Photo Picker and document-provider entry points support multiple
  scoped selections without broad storage permissions; accepted content becomes
  removable pending chips and text/file prompt parts.
- FileParts from history and live updates render on outgoing messages, including
  attachment-only prompts.
- Failed HTTP sends restore the submitted draft and attachments without
  overwriting newer edits; accepted sends remain cleared when a later session
  error is surfaced.

## Validation

- Focused domain, repository, and Session Chat tests pass on both platforms.
- Android debug assembly and iOS simulator build pass.
- Physical-device picker and document-provider exercise remains a release
  validation risk; it does not create an implementation parity gap.

## Remaining work

- No remaining implementation work in OPE-55.
- Exercise native Photos and Files/document providers on release devices before
  shipping.
