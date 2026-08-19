# App Icons

OpenCode Mobile keeps **two** icon sources with different jobs:

| Role | Source | Sync |
| --- | --- | --- |
| On-device launcher (home screen) | [`app-icon/source.svg`](../../app-icon/source.svg) | `npm run app-icon:sync` |
| Store listing (Play / App Store marketing) | [`app-icon/interlock.svg`](../../app-icon/interlock.svg) | `npm run app-icon:sync-store` |

Do not point the launcher generator at `interlock.svg`, and do not replace
`source.svg` with the store mark. Device chrome is the layered “d”; store
listings use the interlocking frames.

This is separate from the in-app Material Icons catalog (see
[`icons.md`](./icons.md)); neither launcher nor store mark is part of
`icons/manifest.yaml`.

## Store listing mark (`interlock.svg`)

Hand-edited square SVG (viewBox `0 0 100 100`). Sync emits:

| Output | Size | Use |
| --- | --- | --- |
| `android/app/src/main/ic_launcher-playstore.png` | 512×512 | Android project Play high-res icon |
| `play-listing/icon-512.png` | 512×512 | Play Console upload kit ([`play-listing/`](../../play-listing/)) |
| `app-icon/store-1024.png` | 1024×1024 | App Store / marketing upload when a separate listing asset is needed |

Phone screenshots live under [`test-screenshots/`](../../test-screenshots/)
(`android_1_*.jpg` / `ios_1_*.jpg`). The feature graphic stays in `play-listing/`.
Only the icon is regenerated from `interlock.svg`.

### Platform notes

- **Play Store** accepts a high-res icon separate from the adaptive launcher, so
  device and listing can differ. That is the intended split.
- **App Store** product-page icon normally comes from the app binary’s
  `AppIcon` (the launcher set). Keep the interlocking mark available as
  `store-1024.png` for Console uploads / promotional art; changing only the
  listing without changing the on-device icon may not be possible through the
  binary App Icon alone.

## Launcher icon (`source.svg`)

The launcher is a **flat** two-layer “d” (body + glass cap). Depth is not
painted into the SVG. iOS 26 applies Liquid Glass to a layered `.icon` package;
Android applies adaptive-icon parallax between background and foreground.

`app-icon/source.svg` is the only hand-edited launcher artifact. The generator
reads two marker comments:

- `<!-- @opencode/background -->` — a single `<rect>` whose `fill` is the
  launcher background color (Android adaptive background and iOS canvas fill).
- `<!-- @opencode/glyph -->` — a `<g>` with a `transform` (Android adaptive-icon
  foreground group transform) and `<path>` children. Paths are emitted verbatim
  into the Android vector drawable.

Glyph fills are a layer contract:

| Fill | Layer |
| --- | --- |
| `#4B4646` (and any non-glass fill) | Body (bowl + stem) |
| `#F1ECEC` | Glass cap (upper-left arc) |

Do not bake shadows, bevels, or gradients into those paths. Tinted / themed /
clear appearances need a clean silhouette; baked lighting fights iOS glass and
collapses under Android theming.

The coordinate space mirrors the Android adaptive-icon foreground vector
(`viewBox="0 0 240 300"`). The source viewBox is intentionally **portrait
(240×300, 4:5)**. Android's adaptive-icon pipeline compensates for the
non-square viewport by pairing the glyph group's `scaleX`/`scaleY` in a 0.8
ratio, so the effective transform on the 108dp square canvas is uniform. iOS
has no such compensation step, so the generator derives the equivalent uniform
transform for a square 108×108 target and renders that into 1024×1024 PNGs and
Icon Composer layer assets. Never rasterize the 240×300 viewBox into a square
PNG without recomputing the transform.

## Launcher generator

`npm run app-icon:sync` runs [`scripts/sync-app-icon.mjs`](../../scripts/sync-app-icon.mjs),
which:

- parses `app-icon/source.svg`,
- rasterizes a flattened 1024×1024 PNG for iOS `universal` and `dark` catalog slots,
- rasterizes a white-on-transparent 1024×1024 silhouette PNG for the iOS 18
  `tinted` catalog slot,
- writes `AppIcon.icon` (Liquid Glass): white body/glass layer PNGs plus
  `icon.json` with per-layer fills, specular, and translucency,
- validates `AppIcon.icon` with `ictool` when Icon Composer is installed
  (Xcode 26+; skipped on Linux CI),
- updates `AppIcon.appiconset/Contents.json` with `filename` references,
- emits Android `drawable/ic_launcher_foreground.xml` (two-tone vector),
- emits Android `drawable/ic_launcher_monochrome.xml` (white silhouette),
- emits `values/ic_launcher_background.xml` and
  `mipmap-anydpi-v26/ic_launcher{,_round}.xml`,
- regenerates pre-API-26 `mipmap-*/ic_launcher{,_round}.png` fallbacks.

The generator uses `rsvg-convert` (librsvg) for SVG→PNG. Install with
`brew install librsvg` (macOS) or `apt install librsvg2-bin` (Linux).

Glass material values live in the generator (`writeIconComposer`), not in
`source.svg`. Re-running sync overwrites `AppIcon.icon`. Preview or nudge
specular in Icon Composer (`open -a "Icon Composer" …/AppIcon.icon`); copy
any keeper settings back into the script before the next sync.

## Launcher outputs

| Platform | Path |
| --- | --- |
| iOS 26+ | `ios/OpenCode Mobile/OpenCode Mobile/AppIcon.icon/` (`.icon` package; `ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon`) |
| iOS catalog fallback | `…/Assets.xcassets/AppIcon.appiconset/{icon-1024,icon-tinted-1024}.png` + `Contents.json` |
| Android API 26+ | `drawable/ic_launcher_foreground.xml`, `drawable/ic_launcher_monochrome.xml`, `values/ic_launcher_background.xml`, `mipmap-anydpi-v26/ic_launcher{,_round}.xml` |
| Android API 24–25 | `mipmap-{m,h,x,xx,xxx}dpi/ic_launcher{,_round}.png` |

## iOS appearance variants (launcher)

`AppIcon.icon` is the iOS 26 source of truth. The system renders Default, Dark,
Clear, and Tinted from the same two layers. The asset-catalog PNGs remain as an
explicit pre-26 / git-diff copy of the flattened mark:

- `universal` / `dark` — flattened two-tone on `#070707`.
- `tinted` — white-on-transparent silhouette from the same glyph paths.

## Editing

### Launcher (device)

1. Edit `app-icon/source.svg` (keep the `@opencode/background` and
   `@opencode/glyph` markers; keep `#F1ECEC` as the glass-cap fill).
2. Run `npm run app-icon:sync`.
3. Commit `app-icon/source.svg` and all generated launcher outputs, including
   `AppIcon.icon`.

Do not hand-edit generated iOS PNGs, `AppIcon.icon` assets, or the Android
vector XML; rerun the generator instead.

### Store listing

1. Edit `app-icon/interlock.svg`.
2. Run `npm run app-icon:sync-store`.
3. Commit `app-icon/interlock.svg`, `app-icon/store-1024.png`,
   `play-listing/icon-512.png`, and
   `android/app/src/main/ic_launcher-playstore.png`.
