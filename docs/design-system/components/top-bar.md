# Top Bar

## Intent

Provide a reusable page-level navigation bar for screens that need an optional
leading navigation affordance, a concise start-aligned title, an optional
single-line subtitle, and up to three compact trailing actions without
introducing feature-specific chrome.

Use it for screen-level navigation and high-priority page actions. Do not use it
as a generic header row inside scroll content or as a container for filters,
search fields, or arbitrary custom content.

## Public API

- Variants:
  - default single-line title.
  - two-line title when `subtitle` and/or `subtitleAccessory` is provided.
- Leading variants:
  - `none`
  - `back`
  - `close`
  - `customIcon`
- States:
  - default
  - divider shown / divider hidden (`showDivider` defaults to **false**)
  - action enabled / disabled
- Required content:
  - action handler for any leading or trailing action that is shown
  - native icon identifier and accessibility label for icon actions
- Optional content:
  - single-line `title`
  - single-line `subtitle`
  - `subtitleAccessory.statusDot` for foreground SSE connection state
  - trailing actions list, up to 3 total

Trailing actions may be text or icon actions, but at most one trailing action
may use a text label in this version.

## Token Usage

- Semantic tokens: `color.background.primary`, `color.surface.secondary`,
  `color.text.primary`, `color.text.secondary`, `color.text.disabled`,
  `color.border.subtle`, `color.icon.strong`.
- Component tokens: `topBar.minHeight`, `topBar.paddingHorizontal`,
  `topBar.paddingVertical`, `topBar.itemSpacing`, `topBar.titleSpacing`,
  `topBar.titleEdgeInsetWhenNoLeading`, `topBar.titleBlockHeight`,
  `topBar.subtitleSpacing`, `topBar.dividerThickness`.
- Typography roles:
  - `type.title` for the top-bar title.
  - `type.caption` for the optional subtitle.
  - `type.button` for labeled trailing actions.

## Behavior

The top bar sits on the screen background by default. An optional bottom divider
adds separation when the screen needs stronger chrome; **divider is off by
default**.

The title is **start-aligned** on both platforms (after any leading control).
When leading is `none`, the title starts at
`topBar.titleEdgeInsetWhenNoLeading` (18) from the screen edge.

The title block always reserves `topBar.titleBlockHeight`, whether the second
line uses plain `subtitle` text, a `subtitleAccessory`, or both. A one-line title
is vertically centered inside that fixed block so switching between one-line and
two-line content does not change overall bar height.

When `subtitleAccessory.statusDot` is set, the second line renders through
`OpenCodeConnectionStatusLabel` (see `connection-status-label.md`) with
lowercase status copy and a semantic dot.
Plain `subtitle` text is ignored while a status-dot accessory is present.

Leading actions use semantic meaning rather than arbitrary placement:

- `back` returns to the previous screen. It also opts a pushed screen into the
  platform-native Back gesture: iOS uses the navigation controller's standard
  leading-edge interactive pop, while Android continues to use system and
  predictive Back. A completed gesture is equivalent to invoking the visible
  Back action; a canceled gesture leaves the current screen active.
- `close` dismisses the current flow.
- `customIcon` is for a product-specific leading affordance that still behaves
  like a compact navigation control.

The title and subtitle are optional, independent, and each truncates to one
line. They should remain concise and page-identifying rather than descriptive
body copy.

Trailing actions are compact and remain secondary to the page title. Icon
actions should supply accessibility labels. Disabled actions stay visible but
block interaction and reduce visual emphasis.

When both `title` and `subtitle` are shown, accessibility combines them into
one label.

## Platform Notes

- iOS: custom SwiftUI bar with start-aligned title. Icon leading/trailing actions
  use iOS 26+ Liquid Glass circles (`.glassEffect` interactive circle) with
  `OpenCodeButton` `iconCircle` material fallback below iOS 26. Labeled trailing
  actions still use `OpenCodeButton` `compactText`. The `back` variant restores
  UIKit's standard leading-edge interactive pop for pushed screens.
- Android: custom Compose bar with start-aligned title. Leading and trailing
  affordances reuse **`OpenCodeButton`** (`IconCircle` / `CompactText`). System
  and predictive Back remain owned by the Navigation 3 host.

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
  with controls to switch leading action, trailing action mode, two-line title,
  status-dot subtitle, divider, and enabled state rather than rendering a static
  example matrix.
- Loading: not applicable for this component version.
- Error: not applicable for this component version.
- Divider: shown and hidden variants are included; default is hidden.

## Parity And Divergence

Shared semantics for leading variants, start-aligned title, subtitle scope,
action composition, fixed title-block height, default-off divider, and Back
navigation are aligned across iOS and Android. Intentional platform mapping for
native Back input and iOS glass icon circles vs Android Material icon circles is
documented in `../divergences.md`.
