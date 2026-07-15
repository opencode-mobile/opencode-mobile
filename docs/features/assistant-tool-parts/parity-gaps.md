# Assistant Tool Parts — Parity Gaps

## Interleaved text blocks render plain text (not per-block markdown)

- **Intent:** Each text timeline block can render as markdown independently when
  tools interrupt a turn (`text | tool | text`).
- **iOS:** Message-level markdown applies only when a message has a single text
  block; additional text blocks after tools render as plain text.
- **Android:** Same single-text-block markdown rule.
- **Area:** Shared Screen Model / content preparation limitation in OPE-49.
- **Reason:** `MessageContentPreparer` still prepares one document from
  TextPart-only `Message.text`; per-block prep was deferred.
- **Next action:** Extend content preparation to attach markdown per text block
  (follow-up under assistant-tool-parts or Session Chat polish).
- **Status:** Temporary.
