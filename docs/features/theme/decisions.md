# Theme System — Decisions

## Theme JSON is the single source of truth

Color values are no longer hardcoded in code. Bundled and imported OC-2 JSON is
parsed at runtime, and both platforms use the same resolver algorithm to produce
the resolved token dictionary. Imported themes flow through the same pipeline as
bundled presets.

## Tokens use OpenCode desktop naming

App-internal token names match the desktop schema (`text-strong`, `surface-base`,
etc.) rather than the earlier `textPrimary`, `backgroundPrimary` convention.
Deprecated iOS aliases were removed after call-site migration (OPE-17).

## Bundled presets mirror opencode desktop

Thirty-seven desktop themes ship in-app. `shared/theme-presets/` is the canonical
repo copy; `npm run themes:sync` copies the same JSON into iOS and Android bundle
locations and regenerates `manifest.json`. The sync script pins an opencode git
ref so preset updates are deliberate.

## Settings separates presets from import

The **Theme** row opens a picker for bundled presets plus any previously
imported themes (imports are appended after bundled entries and deduped by ID).
**Import Theme** remains a separate action for custom OC-2 JSON files. Imports
whose `id` matches a bundled preset are rejected to avoid picker ambiguity.

## Import guardrails

Imported JSON is capped at 512 KB. Validation requires the standard palette
seeds in both light and dark variants. Persist failures roll back the in-memory
selection and surface an error instead of silently dropping the import.

## Persistence keys are aligned across platforms

Both apps persist `selectedThemeID` and serialized imported themes. On launch,
`ThemeManager` restores the last selection from bundled presets or imported
storage. `clearAll()` resets to the bundled OC-2 default.

## iOS bundle layout tolerates Xcode flattening

Xcode may copy `Resources/ThemePresets/*.json` into the app bundle root without
a `ThemePresets/` subdirectory. `ThemeManager` probes `ThemePresets`,
`Resources/ThemePresets`, and bundle root when loading `manifest.json` and
preset files.

## Letter badge colors remain platform-specific component tokens

The 5 accent pairs (violet, teal, amber, blue, rose) have no desktop theme
equivalent. They stay as component-local constants, not resolved from the theme
JSON. Documented in `docs/design-system/divergences.md`.
