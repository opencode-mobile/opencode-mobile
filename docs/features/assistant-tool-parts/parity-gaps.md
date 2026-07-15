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

## File-change tools still use generic chrome

- **Intent:** File-change tools show `Edit`/`Write`/`Patch` + path (or
  `N files` for multi-file `apply_patch`) + optional +/−; single-file tap opens
  owning-turn File Viewer (list fallback); multi-file patch tap opens the
  Changed Files list.
- **iOS:** `edit` / `write` / `apply_patch` still render as generic
  `Called {tool}` cards.
- **Android:** Same generic chrome for those tool IDs.
- **Area:** Missing feature wiring (OPE-51).
- **Reason:** OPE-50/OPE-52 scoped other families; file-change is OPE-51.
- **Next action:** Implement
  [OPE-51](https://linear.app/opencode-mobile/issue/OPE-51/file-change-tool-cards)
  per grilled decisions (no nested accordion / expand / sheet).
- **Status:** Temporary.
