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

## File-change and task tools still use generic chrome

- **Intent:** File-change tools show filename +/− and open Changes; `task` shows
  subagent chrome and navigates to a child session when available.
- **iOS:** `edit` / `write` / `apply_patch` / `task` still render as generic
  `Called {tool}` cards (OPE-50 left them for later tickets).
- **Android:** Same generic chrome for those tool IDs.
- **Area:** Missing feature wiring (OPE-51 / OPE-52).
- **Reason:** OPE-50 scoped only context/shell/network/interact families.
- **Next action:** Implement [OPE-51](https://linear.app/opencode-mobile/issue/OPE-51/file-change-tool-cards)
  and [OPE-52](https://linear.app/opencode-mobile/issue/OPE-52/subagent-task-tool-card).
- **Status:** Temporary.
