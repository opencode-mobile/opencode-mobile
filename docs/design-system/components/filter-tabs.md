# Filter Tabs

## Intent

Provide a horizontal, single-select filter row with optional count badges for
scoping a list (for example home Session List by project, or **All**).

Use this when a screen needs compact filter chips inspired by messaging-style
tab rows: label plus count, selected capsule outline, and neutral count badges.

## Public API

- Models:
  - `OpenCodeFilterTab` / `FilterTab`: `id`, `title`, optional `count`.
- Required content: ordered `tabs` collection.
- Required state: `selectedTabId`.
- Required callbacks: `onTabSelected(id)`.
- States: default (unselected tab), selected tab. Pressed is native transient
  feedback. Disabled, loading, error, and empty-row states are not applicable;
  an empty `tabs` collection renders nothing.

## Token Usage

- Semantic tokens: `text.strong` / `text.base` for labels; `border.weak` for
  selected capsule outline; `surface.raised` / muted text for count badges
  (neutral in both selected and unselected — no accent/interactive fill).
- Component tokens: see `filterTabs.*` in `docs/design-system/tokens.md`.
- Typography roles: `type.button` / label-medium for tab titles; caption-scale
  for count numerals.

## Behavior

The component is stateless. Callers own selection and tab models (including
counts). Tapping a tab calls `onTabSelected` with that tab’s `id`.

Tabs scroll horizontally when they overflow. Selected tab shows a capsule
stroke around label + count. Count badges are circular; omit the badge when
`count` is null. Zero is a valid shown count when provided.

Accessibility: each tab is a button with selected state when active; include
count in the accessibility label when present.

## Platform Notes

- iOS: `OpenCodeFilterTabs` under `Components`, SwiftUI `ScrollView` + `HStack`.
- Android: `OpenCodeFilterTabs` under `ui/components`, Compose
  `Row` + `horizontalScroll`.

## Review Surfaces

Both Component Galleries include an interactive **Filter Tabs** entry with
**All** plus sample project filters and live selection. Light and dark use the
shared gallery theme control. Session List gallery also shows the tabs in
context.

## Parity And Divergence

Same conceptual API on both platforms. No intentional divergences.

## Completion Ledger

- Public API and states: complete.
- Token and typography mapping: complete.
- iOS and Android implementations: complete.
- Gallery and previews: complete.
- Parity and divergences: complete; none known.
- Automated checks: covered by platform build for affected surfaces.
