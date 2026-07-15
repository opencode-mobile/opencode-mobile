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

## File-change v1

- Collapsed Edit/Write/Patch rows show filename and +N/−N when available.
- Tap opens the existing Changes review experience; do not build a full inline
  diff editor in the chat timeline for v1.

## Visibility

- Match desktop hide rules for `todowrite` and in-flight `question`.
- Live permission/question UX remains owned by Permission Approval.
