# Assistant Tool Parts — Decisions

## Ticket slicing

- Keep domain enrichment and design-system Tool Call Card as parallel foundations.
- Ship generic tool cards in Session Chat before specialized families.
- Merge simple specialized families (context, shell, network, interact) into one
  lightweight chrome ticket.
- Keep file-change tools and subagent task as separate tickets (navigation and
  diff-stat presentation).

## OPE-48 domain shape (grilling)

- ToolPart is structured: tool status + name + title/error + opaque
  input/output/metadata (+ call id). Not a preformatted status string.
- Compact emoji labels are presentation via a display formatter; they are not
  stored on ToolPart.
- Missing or unrecognized server status coerces to `pending`.
- OPE-48 stores card-ready fields now so later tickets do not reshape the domain.
- Include call id when the server provides one.
- Until tool cards land, `Message.text` still joins TextPart text with
  formatter-built compact tool labels so Session Chat looks unchanged.
- Domain ToolPart omits `raw`, tool timing, and attachments for OPE-48; DTOs
  may still parse them. Android tool-state DTO gains `metadata` parity with iOS.
- Compact label formatter stays domain-side (beside the message adapter) so
  both platforms keep the same `Message.text` projection until cards land.
- Completed edit/write/patch may already carry filediff-style metadata in
  OPE-48; compact labels stay dumb (`✓ edit`) until OPE-51 uses that metadata.
- Tool input and metadata are opaque string-keyed maps; output and error are
  optional strings. No per-tool domain schemas in OPE-48.

## OPE-47 Tool Call Card (grilling)

- Collapsed-only for this ticket: status, title, optional subtitle, optional
  +/− counts, optional error text, optional feature-owned tap. No expand,
  chevron, or body slot — add those when a later ticket needs them (likely
  OPE-50 for shell output).
- One component with four status variants (`pending` / `running` /
  `completed` / `error`). Do not ship a separate error card component.
- Pending/running use static status chrome only; no shimmer/animation in
  OPE-47.
- Optional +/− accessory is part of the public API now so OPE-51 does not
  reshape the card; the component does not compute diff stats.
- Card is tool-agnostic: feature code builds title/subtitle strings (for
  example “Called edit”). The design-system card does not know tool IDs.
- Optional tap is feature-owned; without a handler the card is display-only.
  The card does not navigate to Changes or child sessions itself.

## File-change v1

- Collapsed Edit/Write/Patch rows show filename and +N/−N when available.
- Tap opens the existing Changes review experience; do not build a full inline
  diff editor in the chat timeline for v1.

## Visibility

- Match desktop hide rules for `todowrite` and in-flight `question`.
- Live permission/question UX remains owned by Permission Approval.

## OPE-49 generic Session Chat cards (grilling)

- Keep one timeline item per **message**, with ordered **timeline blocks**
  inside (text block | tool card block). Do not flatten parts into sibling
  list rows.
- Session Chat builds blocks from structured parts. `Message.text` is
  TextPart-only (adjacent TextParts merged with newlines); tools are not
  joined into `text` for chat display.
- Hide rules live in the Screen Model when building blocks, not by dropping
  parts from the domain: always hide `todowrite`; hide `question` while
  pending/running; show completed `question` as generic until OPE-50.
- If a message has no visible blocks after hide rules, omit it from the
  timeline (no empty shell).
- OPE-49 chrome is generic for every tool ID (including `bash` / `edit` /
  `task`): title `Called {toolName}`; optional subtitle = running
  `ToolPart.title` only; error status keeps that title and uses the card
  error slot. No tap, expand, or input-key subtitle heuristics.
- Only assistant messages get tool card blocks; other roles stay text-only.
- Rebuild blocks from latest parts on each live apply (status updates in
  place). Markdown applies only to text blocks.
- Compact tool-label formatter leaves the Session Chat path; keep the helper
  until nothing else needs it, then delete.
- Specialize families in OPE-50 / OPE-51 / OPE-52; do not start that chrome
  in OPE-49.
