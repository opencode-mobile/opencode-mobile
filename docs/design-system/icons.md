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

### iOS sizing (required)

`OpenCodeIconImage` is an asset-catalog template, not an SF Symbol.
**Size with `size:` or an outer `.frame` — never `.font`.** `.font` only scales
SF Symbols; on catalog assets it is ignored, so glyphs draw at intrinsic size
and look edge-to-edge inside tight chrome (remove circles, status marks).

```swift
// Explicit glyph size
OpenCodeIconImage(icon: .close, size: 18)

// Fill proposed size after padding (matches Android Icon + padding)
OpenCodeIconImage(icon: .close)
    .padding(4)
    .frame(width: 20, height: 20)
```

Android `Icon(..., Modifier.size(n).padding(inset))` is the corresponding
pattern when a glyph sits inside circular chrome smaller than a button.

## Product usage

Some catalog icons have a stable product meaning beyond gallery samples:

| Icon | Usage |
| --- | --- |
| `widgets` | All-projects mark on the Session List scope control and Filter by project sheet |
| `add` | Generic add / plus affordances |
| `create_new_folder` | **Add project** (Session List Dock and home add-project actions) |
| `keyboard_arrow_down` | Session List Dock scope-control chevron; title switcher chevron after home server name |
| `search` | Gallery and Top Bar search samples |
| `folder_check` | **Current directory** row in Add project sheet |
| `subdirectory_arrow_right` | Subdirectory rows in Add project sheet |
| `arrow_upward` | **Go up** row in Add project sheet |
| `edit` | Edit/rename affordances in gallery and samples; not the gallery entry on home |
| `archive` | Session Chat overflow **Archive** action |
| `delete` | Edit server top-bar **Remove** |
| `arrow_downward` | **Scroll to end** action pill on session chat |
| `radio_button_checked` | Selected question-request option / custom answer |
| `radio_button_unchecked` | Unselected question-request option / custom answer |

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
