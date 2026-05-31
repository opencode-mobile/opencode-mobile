# Theme System — Decisions

## Theme JSON is the single source of truth

Color values are no longer hardcoded in code. The bundled `oc-2.json` is parsed
at runtime, and both platforms use the same resolver algorithm to produce the
resolved token dictionary. Imported themes flow through the same pipeline.

## Tokens use OpenCode desktop naming

App-internal token names match the desktop schema (`text-strong`, `surface-base`,
etc.) rather than the earlier `textPrimary`, `backgroundPrimary` convention.
This creates a stable vocabulary shared across all OpenCode clients.

## Letter badge colors remain platform-specific component tokens

The 5 accent pairs (violet, teal, amber, blue, rose) have no desktop theme
equivalent. They stay as component-local constants, not resolved from the theme
JSON. Documented in `docs/design-system/divergences.md`.
