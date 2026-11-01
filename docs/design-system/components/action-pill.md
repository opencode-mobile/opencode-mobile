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

- Semantic tokens: `surface-raised-base`, `text-strong`, `icon-strong-base`,
  `border-weaker-base` (fallback / Android stand-in).
- Component tokens: geometry and elevation — see `actionPill.*` in
  `docs/design-system/tokens.md`. Shadow elevation is Android / pre–iOS 26
  fallback only.
- Typography roles: `type.button`.

## Behavior

Tapping triggers the provided action when enabled. Disabled pills reduce visual
emphasis and block interaction.

The pill is designed to float centered above content (for example above the
composer). Callers position it with platform layout; the component supplies
capsule geometry and floating glass chrome shared with the Floating and
Review Changes pills.

Callers should hide the pill while the user is actively scrolling and reveal
it again after scrolling settles when the distance threshold is still met.

Accessibility labels:

- `scrollToEnd`: **Scroll to end**.

Pressed feedback uses subtle scale/opacity (not interactive Liquid Glass lens).

## Platform Notes

- iOS 26+: non-interactive `glassEffect(.regular)` capsule with
  `surface-raised-base` underlay (shared `OpenCodeFloatingPillChrome`). Below
  iOS 26: raised capsule + border + light shadow. Float stacks that show more
  than one glass pill should wrap in `GlassEffectContainer`.
- iOS session chat temporarily uses eager SwiftUI timeline layout for reliable
  scroll-to-end behavior; see
  `docs/design-system/divergences.md`.
- Android: Material 3 raised `Surface` stand-in (fill alpha `0.95`, light
  elevation, weaker border) — same floating-chrome family as the composer /
  dock; no system Liquid Glass API. See
  `docs/design-system/divergences.md`.

## Previews

- Light and dark previews exist in each platform component file.
- The component gallery includes an interactive **Action pill** entry with
  enabled/disabled controls.
- The **Message List** gallery entry provides a full-height scrollable
  timeline with plain outgoing and incoming messages for live scroll-to-end
  pill testing.

## Parity And Divergence

Shared semantics for variant, states, and geometry are aligned across iOS and
Android. Material mapping follows the intentional Floating chrome divergence.
