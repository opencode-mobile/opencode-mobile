# Action Pill

## Intent

Provide a compact **floating pill** for contextual overlay actions that should
appear above scrolling content without competing with primary screen chrome.
The first product use is **scroll to end** on the session chat timeline when
the latest messages are not visible.

Feature screens own **when** the pill appears (for example when the last message
is off-screen or the viewport is more than about **120pt / 20% of viewport
height** from the bottom). The component only defines **how** the pill looks
and behaves when shown.

## Public API

- Variants:
  - `scrollToEnd` — fixed label **Scroll to end** with a leading down-arrow icon.
- States: **enabled**, **disabled**.
- Required content: variant (or label for future custom variants).
- Interaction: **`onClick`** / **`action`** when enabled.

## Token Usage

- Semantic tokens: `surface-raised-base`, `surface-raised-base-hover`,
  `text-strong`, `icon-strong-base`, `border-weaker-base`.
- Component tokens: geometry and elevation — see `actionPill.*` in
  `docs/design-system/tokens.md`.
- Typography roles: `type.button`.

## Behavior

Tapping triggers the provided action when enabled. Disabled pills reduce visual
emphasis and block interaction.

The pill is designed to float centered above content (for example above the
composer). Callers position it with platform layout; the component supplies
capsule geometry, raised surface fill, border, and shadow.

Callers should hide the pill while the user is actively scrolling and reveal
it again after scrolling settles when the distance threshold is still met.

Accessibility labels:

- `scrollToEnd`: **Scroll to end**.

Pressed feedback uses native button interaction with shared token mapping.

## Platform Notes

- iOS: `Button` with plain style, capsule background, and light shadow.
- iOS session chat uses `ScrollView` + `LazyVStack` with
  `.defaultScrollAnchor(.bottom)` for scroll-to-end and Follow Latest; see
  `docs/design-system/divergences.md`.
- Android: Material 3 `Surface(onClick = ...)` with capsule shape, border, and
  shadow elevation.

## Previews

- Light and dark previews exist in each platform component file.
- The component gallery includes an interactive **Action pill** entry with
  enabled/disabled controls.
- The **Message List** gallery entry provides a full-height scrollable
  timeline with plain outgoing and incoming messages for live scroll-to-end
  pill testing.

## Parity And Divergence

Shared semantics for variant, states, and geometry are aligned across iOS and
Android. No intentional divergence.
