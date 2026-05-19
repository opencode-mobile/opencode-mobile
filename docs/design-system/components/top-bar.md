# Top Bar

## Intent

Provide a reusable page-level navigation bar for screens that need an optional
leading navigation affordance, a concise single-line title, and up to three
compact trailing actions without introducing feature-specific chrome.

Use it for screen-level navigation and high-priority page actions. Do not use it
as a generic header row inside scroll content or as a container for subtitles,
filters, search fields, or arbitrary custom content.

## Public API

- Variants: default page-level top bar.
- Leading variants:
  - `none`
  - `back`
  - `close`
  - `customIcon`
- States:
  - default
  - divider shown / divider hidden
  - action enabled / disabled
- Required content:
  - action handler for any leading or trailing action that is shown
  - native icon identifier and accessibility label for icon actions
- Optional content:
  - single-line `title`
  - trailing actions list, up to 3 total

Trailing actions may be text or icon actions, but at most one trailing action
may use a text label in this version.

## Token Usage

- Semantic tokens: `color.background.primary`, `color.surface.secondary`,
  `color.text.primary`, `color.text.secondary`, `color.text.disabled`,
  `color.border.subtle`, `color.icon.strong`.
- Component tokens: `topBar.minHeight`, `topBar.paddingHorizontal`,
  `topBar.paddingVertical`, `topBar.itemSpacing`, `topBar.titleSpacing`,
  `topBar.dividerThickness`.
- Typography roles:
  - `type.title` for the top-bar title.
  - `type.button` for labeled trailing actions.

## Behavior

The top bar sits on the screen background by default. An optional bottom divider
adds separation when the screen needs stronger chrome.

Leading actions use semantic meaning rather than arbitrary placement:

- `back` returns to the previous screen.
- `close` dismisses the current flow.
- `customIcon` is for a product-specific leading affordance that still behaves
  like a compact navigation control.

The title is optional and truncates to one line. It should remain concise and
page-identifying rather than descriptive body copy.

Trailing actions are compact and remain secondary to the page title. Icon
actions should supply accessibility labels. Disabled actions stay visible but
block interaction and reduce visual emphasis.

## Platform Notes

- iOS: implemented as a custom SwiftUI component that keeps the title centered
  while reusing **`OpenCodeButton`** (`iconCircle` / `compactText`) for leading
  icon actions and trailing actions.
- Android: implemented as a custom Compose component with start-aligned title.
  Leading and trailing affordances reuse **`OpenCodeButton`** (`IconCircle` /
  `CompactText`) so ripple and shape match the shared button component.

Leading **back** / **close** use fixed catalog icons; **customIcon** remains
caller-provided per platform catalog types (`OpenCodeIcon` / `OpenCodeIcons`) in
`../divergences.md`.

## Previews

- Light: included.
- Dark: included.
- Default: included.
- Pressed: covered through native button interaction behavior.
- Disabled: included through disabled trailing action examples.
- Gallery interaction: the component gallery uses a single live top-bar preview
  with controls to switch leading action, trailing action mode, divider, and
  enabled state rather than rendering a static example matrix.
- Loading: not applicable for this component version.
- Error: not applicable for this component version.
- Divider: shown and hidden variants are included.

## Parity And Divergence

Shared semantics for leading variants, title scope, action composition, and
divider behavior are aligned across iOS and Android. The intentional title
alignment difference is documented in `../divergences.md`.
