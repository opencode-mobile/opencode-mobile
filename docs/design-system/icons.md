# Icons

OpenCode Mobile uses [Google Material Icons](https://github.com/google/material-design-icons)
(filled, 24dp) on **both** iOS and Android so glyphs match across platforms.

Icons are vendored at build time. Most entries come from the
[`@material-design-icons/svg`](https://www.npmjs.com/package/@material-design-icons/svg)
npm package (classic Material Icons, filled 24dp). Icons marked `source: material_symbols`
in the manifest are fetched from Google's
[Material Symbols](https://github.com/google/material-design-icons/tree/master/symbols)
repo during `icons:sync`. The mobile apps do not download icons at runtime.

## Catalog

The canonical list lives in [`icons/manifest.yaml`](../../icons/manifest.yaml).
Each entry maps to:

- Android: `OpenCodeIcons.{Name}` in
  [`OpenCodeIcons.kt`](../../android/app/src/main/java/dev/opencode/mobile/ui/icons/OpenCodeIcons.kt)
  (`ImageVector`)
- iOS: `OpenCodeIcon.{name}` in
  [`OpenCodeIcon.swift`](../../ios/OpenCode Mobile/OpenCode Mobile/Icons/OpenCodeIcon.swift)
  with assets under
  [`MaterialIcons.xcassets`](../../ios/OpenCode%20Mobile/OpenCode%20Mobile/MaterialIcons.xcassets)

Use `OpenCodeIconImage(icon:)` on iOS for template-rendered icons that follow
`OpenCodeColors.iconDefault` / `iconStrong` tints.

## Product usage

Some catalog icons have a stable product meaning beyond gallery samples:

| Icon | Usage |
| --- | --- |
| `widgets` | Home top-bar action that opens the developer-only Component Gallery (`accessibilityLabel`: Developer components) |
| `add` | **Browse projects** list row on home (start decorator) |
| `edit` | Edit/rename affordances in gallery and samples; not the gallery entry on home |

Manifest `notes` may mention SF Symbol equivalents; feature decisions live under
`docs/features/<feature-slug>/decisions.md`.

## Adding an icon

1. Add an entry to `icons/manifest.yaml` (`name`, `style`, optional `source`, optional `notes`).
   Use `source: material_symbols` only when the glyph is not in the classic npm package.
2. Run `npm install` (first time) and `npm run icons:sync` from the repo root.
3. Commit generated outputs: `OpenCodeIcons.kt`, `OpenCodeIcon.swift`,
   `MaterialIcons.xcassets/**`, and `icons/.manifest-hash`.
4. Use the new symbol in feature or design-system code.

`npm run icons:check` verifies the manifest hash and that generated files exist
(useful in CI).

`npm run icons:audit` scans iOS and Android source for `OpenCodeIcons` /
`OpenCodeIcon` references and reports drift against `icons/manifest.yaml`
(errors when code uses an icon not in the manifest; warns on unused manifest
entries).

Material Symbols SVGs use a `viewBox` origin such as `0 -960 960 960`. The
Android generator (`scripts/lib/android-generator.mjs`) applies a `group`
translation so path data stays inside the `ImageVector` viewport; without that
offset, glyphs can render blank on Android while iOS assets still look correct.

## Licensing

Material Icons are licensed under the
[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0). Attribution
in an in-app About screen is appreciated but not required.
