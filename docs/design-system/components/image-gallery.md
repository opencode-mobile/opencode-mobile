# Image Gallery and Image Viewer

## Intent

`OpenCodeImageGallery` shows a group of images inline in Session Chat, under
whatever produced them: a tool card or tool group, a user message, or an
assistant reply. `OpenCodeImageViewer` opens over the whole group full screen.
One image keeps today's inline image; the gallery replaces the single-image
component and its one-image viewer.

## Public API

- Item: stable id, optional file name, optional decoded preview, and an
  optional image source that loads the full picture. The source is an opaque
  async loader; how bytes are fetched and decoded stays with the caller.
- Gallery: items in order and an optional open action that receives the
  tapped index. Without an open action tiles are not interactive.
- Viewer: items, the shown index, an index-change action, and a dismiss
  action. The caller owns the shown index (Screen Model state).
- Layout by count (not a caller option):
  - 1: the image at its own aspect ratio, height capped at 220 pt.
  - 2: two equal columns of 3:4 tiles.
  - 3: one large tile on the left (1.4 : 1 width ratio) and two stacked tiles
    on the right, 230 pt tall.
  - 4 or more: a 2×2 grid of 4:3 tiles. With more than four images the fourth
    tile carries a dimmed `+N` overlay, where N = count − 3.
- Tile states: loaded, loading, and failed. A tile with a preview shows it at
  once and swaps in the full picture when it loads. A tile with neither a
  preview nor a picture after loading is failed.

The public APIs accept presentation-safe values only. Domain models, server
DTOs, data URLs, raw bytes, colors, radii, and typography overrides are not
component inputs.

## Token Usage

- Semantic tokens: `surface-raised-base` for placeholders and failure tiles,
  `border-weaker-base` for the tile border, `text-weak` and `text-weaker` for
  failure text, `icon-weak-base` for the failure glyph.
- Radius: the small radius on every tile; 4 pt gaps between tiles.
- Typography roles: `type.caption` for file name captions and failure text,
  `type.title` for the `+N` overlay, `type.heading` for the viewer index label.
- Captions and the `+N` overlay sit on a dark scrim over the picture, so
  their text is white in both themes. The viewer is always black.

## Behavior

- Each tile loads its own picture when it appears, so only on-screen tiles
  decode. Loading is off the main thread and cached with a memory bound by
  the caller's loader.
- Loading tiles show a neutral pulsing placeholder (still under Reduce
  Motion). Failed tiles show a muted failure tile with the file name, are not
  tappable, and never block other tiles.
- Mosaic tiles may show the file name as a small caption; the `+N` tile does
  not.
- The viewer pages horizontally over every item, shows "i of n" and the file
  name, zooms by pinch or double tap, dismisses by swiping down (when not
  zoomed) or the close control, and shares the shown picture through the
  system share sheet, which also offers saving to Photos.
- Accessibility: tiles are buttons labelled "Image i of n, <file name>" (a
  single image reads "Image, <file name>"); the `+N` tile adds how many more
  images it stands for. Viewer pages use the same labels. Loading and failed
  tiles say so.

## Platform Notes

- iOS: SwiftUI layout for the mosaic, a `UIScrollView` per viewer page for
  zoom, and `UIActivityViewController` for share. The share control uses the
  native share glyph until the icon catalog has a share icon.
- Android: Compose layout and a Material 3 full-screen pager with the same
  states, labels, and actions. The share control is a text button until the
  icon catalog has a share icon.

## Review Surfaces

- iOS Component Gallery: Image Gallery (every count, loading and failed
  tiles, inside an outgoing message) and Image Viewer (one image, a group
  opened at the second image, a group with a failed image).
- Android Component Gallery: the same entries.

## Parity And Divergence

No intentional divergence is defined. Track temporary differences on the
GitHub issue.
