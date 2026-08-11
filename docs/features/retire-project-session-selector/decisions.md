# Retire Project Session Selector — Decisions

## Session List is the only home mode

- Decision: Remove Project Session Selector and the dual-mode preference. Home
  always uses Session List with the floating dock.
- Reason: The settings toggle was already hidden and Session List forced on for
  everyone; the selector path was dead code. Keeping two home UIs doubles
  maintenance for focus, See all, and preview truncation that Session List does
  not need.
- Alternatives considered: Keep the component in the gallery only; leave the
  preference flag for a possible restore. Rejected — unused gallery surface and
  preference keys add confusion without a product path back.
