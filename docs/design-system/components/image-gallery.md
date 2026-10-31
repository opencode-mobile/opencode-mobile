# Image Gallery

## Intent

`OpenCodeImageGallery` shows a group of images inline in a Session Chat row:
under the tool card or tool group that returned them, inside an outgoing
message above its text, or after an assistant's text. `OpenCodeImageGalleryViewer`
opens any tile full screen and pages through the whole group. A lone image
keeps the single-image layout; the viewer is the same for one image or many.

## Public API

- Content: an ordered list of gallery images. Each image has a stable id that
  names its picture and an optional file name.
- Gallery action: tap a tile, reported with that image's index in the list.
- Viewer inputs: the images, the page to show, and the actions page changed,
  dismiss, and (optional) share for the current page.
- Loading: the owning feature provides a loader that returns the picture for
  an id. The component asks for it, decodes it, and caches it.

The public APIs accept presentation-safe values only. Domain models, server
DTOs, data URLs, raw bytes, colors, radii, and typography overrides are not
component inputs.

## Layout By Count

| Count | Layout |
| --- | --- |
| 1 | The whole image at its own aspect ratio, at most 220 logical pixels tall, never cropped. |
| 2 | Two equal columns of 3:4 tiles. |
| 3 | One large tile (about 1.4 : 1 width) beside two stacked tiles, 230 logical pixels tall. |
| 4 or more | A 2×2 grid of 4:3 tiles. Past four, the fourth tile carries a dimmed `+N` with N = count − 3. |

Tiles are 4 logical pixels apart, clipped to the small radius, with a weak
border. Multi-tile layouts crop to fill and show the file name as a caption
over a bottom gradient.

## States

- Loading: a neutral shimmer (static when system animations are off).
- Ready: the picture. A small in-memory preview may show first.
- Failed: a muted tile with the file name and "Couldn't load". It is not
  tappable and never blocks the other tiles. The overflow tile stays tappable.

## Viewer Behavior

- Horizontal paging over the group, starting at the tapped image.
- Pinch zoom and double-tap zoom; a zoomed page pans and pauses paging.
- Swipe down or the close control dismisses.
- The title reads `i of n` with the file name below it.
- Share sends the current image to the system share sheet when the owner
  provides it.

## Accessibility

- Each tile is a button labeled `Image i of n, <file name>`; the overflow tile
  adds `, N more`.
- Each viewer page has the same label. The close control is labeled `Close`.

## Token Usage

- Semantic tokens: `surface-raised-base` (tile and shimmer base),
  `border-weaker-base` (tile border), `text-weaker` (shimmer highlight),
  `text-weak` and `icon-weak-base` (failure tile).
- The caption gradient, overflow dim, and viewer chrome are fixed dark scrims
  with white text, because they sit on pictures rather than theme surfaces.
- Component tokens: reuse the small radius; no new token.

## Performance

- Pictures decode off the main thread with power-of-two downsampling: 1280
  pixels on the longest edge inline, 2560 in the viewer.
- Decoded pictures go to a bounded in-memory cache, sized from available
  memory, so scrolling back paints without decoding again.
- Only composed tiles load, so a large group decodes at most four inline
  pictures.

## Review Surfaces

- Component Gallery: Image Gallery, with one to six images, a failed tile, a
  loading pair, a sent group in an outgoing message, and the viewer.
- Snapshot mocks: a shared seed of generated mock screens with the same names
  on both platforms.

## Parity And Divergence

No intentional divergence is defined. Track temporary differences on the
GitHub issue.
