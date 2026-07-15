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

## No specialized tool chrome yet

- **Intent:** Context, shell, network, interact, file-change, and task tools get
  family-specific chrome beyond generic `Called {tool}` cards.
- **iOS:** Generic Tool Call Cards in Session Chat (OPE-49); specialized families
  not started.
- **Android:** Generic Tool Call Cards in Session Chat (OPE-49); specialized
  families not started.
- **Area:** Missing feature wiring (OPE-50+).
- **Reason:** OPE-49 deliberately ships generic chrome only.
- **Next action:** Implement [OPE-50](https://linear.app/opencode-mobile/issue/OPE-50/lightweight-tool-chrome),
  then [OPE-51](https://linear.app/opencode-mobile/issue/OPE-51/file-change-tool-cards) and
  [OPE-52](https://linear.app/opencode-mobile/issue/OPE-52/subagent-task-tool-card).
- **Status:** Temporary.
