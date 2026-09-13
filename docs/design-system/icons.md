# Icons

OpenCode Mobile uses [Lucide](https://lucide.dev/) (24dp outline strokes) on
**both** iOS and Android so glyphs match across platforms.

Icons are vendored at build time from the
[`lucide-static`](https://www.npmjs.com/package/lucide-static) npm package.
The mobile apps do not download icons at runtime.

## Catalog

The canonical list lives in [`icons/manifest.yaml`](../../icons/manifest.yaml).
Each entry has a stable product `name` and a `lucide` source glyph. Product
names stay in feature and component APIs; `lucide` is the Lucide icon id from
https://lucide.dev/icons.

Each entry maps to:

- Android: `OpenCodeIcons.{Name}` in
  [`OpenCodeIcons.kt`](../../android/app/src/main/java/dev/opencode/mobile/ui/icons/OpenCodeIcons.kt)
  (`ImageVector`, stroke-based)
- iOS: `OpenCodeIcon.{name}` in
  [`OpenCodeIcon.swift`](../../ios/OpenCode Mobile/OpenCode Mobile/Icons/OpenCodeIcon.swift)
  with assets under
  [`LucideIcons.xcassets`](../../ios/OpenCode%20Mobile/OpenCode%20Mobile/LucideIcons.xcassets)

Use `OpenCodeIconImage(icon:)` on iOS for template-rendered icons. Product
chrome (list rows, overflow, sheets, the catalog gallery) tints with
`icon-strong-base` so glyphs read with `text-strong` labels. Keep `icon-base`
for muted tracks and secondary marks, not for default action icons.

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

| Icon | Lucide | Usage |
| --- | --- | --- |
| `widgets` | `layout-grid` | All-projects mark on the Session List scope control and Filter by project sheet |
| `add` | `plus` | Generic add / plus affordances; Commands tile **New** |
| `slash` | `square-slash` | Session Composer **Commands** action |
| `create_new_folder` | `folder-plus` | **Add project** (Session List Dock and home add-project actions) |
| `keyboard_arrow_down` | `chevron-down` | Session List Dock scope-control chevron; title switcher chevron after home server name |
| `search` | `search` | Gallery and Top Bar search samples |
| `terminal` | `square-terminal` | open Terminal from home; Commands tile **Terminal** |
| `folder_check` | `folder-check` | **Current directory** row in Add project sheet |
| `subdirectory_arrow_right` | `corner-down-right` | Subdirectory rows in Add project sheet |
| `arrow_forward` | `arrow-right` | Terminal control strip Right |
| `arrow_upward` | `arrow-up` | **Go up** row in Add project sheet; Queue / default Send |
| `arrow_warm_up` | `arrow-up-right` | Steer follow-up send |
| `edit` | `pencil` | Edit/rename affordances in gallery and samples; not the gallery entry on home |
| `archive` | `archive` | Session Chat overflow **Archive** action |
| `delete` | `trash-2` | Edit server top-bar **Remove** |
| `arrow_downward` | `arrow-down` | **Scroll to end** action pill on session chat |
| `radio_button_checked` | `circle-dot` | Selected question-request option / custom answer |
| `radio_button_unchecked` | `circle` | Unselected question-request option / custom answer |
| `undo` | `undo-2` | Commands tile **Undo** |
| `redo` | `redo-2` | Commands tile **Redo** |
| `call_split` | `git-fork` | Commands tile **Fork** |
| `vertical_align_center` | `fold-vertical` | Commands tile **Compact** |

Track feature-specific icon follow-ups in Linear.

## Adding an icon

1. Add an entry to `icons/manifest.yaml` (`name`, `lucide`, optional `notes`).
   `name` is the product identifier used in app code. `lucide` is the Lucide
   icon id (kebab-case) from https://lucide.dev/icons. If `lucide` is omitted,
   the sync script converts `name` from snake_case to kebab-case.
2. Run `npm install` (first time) and `npm run icons:sync` from the repo root.
3. Commit generated outputs: `OpenCodeIcons.kt`, `OpenCodeIcon.swift`,
   `LucideIcons.xcassets/**`, and `icons/.manifest-hash`.
4. Use the new symbol in feature or design-system code.

`npm run icons:check` verifies the manifest hash and that generated files exist
(useful in CI).

`npm run icons:audit` scans iOS and Android source for `OpenCodeIcons` /
`OpenCodeIcon` references and reports drift against `icons/manifest.yaml`
(errors when code uses an icon not in the manifest; warns on unused manifest
entries).

Lucide SVGs are stroke-based (`fill="none"`, `stroke="currentColor"`). The
catalog overrides Lucide's default `stroke-width="2"` to **1.5** in the 24×24
viewBox (`ICON_STROKE_WIDTH` in `scripts/lib/utils.mjs`). The Android generator
(`scripts/lib/android-generator.mjs`)
converts `path`, `circle`, `ellipse`, `line`, `polyline`, `polygon`, and `rect`
elements into stroked `ImageVector` paths. iOS assets keep the SVG strokes and
replace `currentColor` with black so template tinting works in the asset
catalog.

## Licensing

Lucide icons are licensed under the
[ISC License](https://lucide.dev/license).
