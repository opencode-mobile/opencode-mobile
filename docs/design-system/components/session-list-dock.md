# Session List Dock

## Intent

Provide the floating bottom card for Session List home chrome: optional create
error, project **filter chrome** (chip scroller or a single scope control), and
Add project / New session actions.

Use when a screen needs this product dock above a scrolling list. Prefer the
lower-level `OpenCodeFloatingDock` surface only when composing different dock
content.

## Public API

- Required: ordered filter tabs (`id`, `title`, `subtitle`, `count`, optional
  letter + accent), `selectedFilterId`, `onAddProject()`, `onNewSession()`.
- Filter chrome: `useProjectPicker` (default off). Off uses Filter Chip
  `onFilterSelected(id)` plus optional selected-project `onRemoveProject(id)`.
  On uses `onOpenProjectPicker()` for tap on the scope control. Horizontal
  drag on the control does not change the selected project.
- Optional: `createError` string above the filter chrome.
- States: default. Disabled/loading are not applicable; callers gate actions.
  Empty `filterTabs` collapses the filter chrome and still shows the action row.

Tab `id` `all` is the cross-project scope. Chip chrome labels it **All**; picker
chrome labels it **All projects**. Other ids match a worktree. Callers decide
whether that tab is present; the dock does not insert it.

## Token Usage

- Surface: Floating Dock chrome (`surface.raised` underlay / glass materials).
- Chip scroller (default): Filter Chip tokens; selected project may show
  remove.
- Scope control (picker on): `text.strong` fill at **0.08**, 14pt corner,
  letter badge or All mark (32 circle + `widgets` grid), body-secondary
  semibold title, caption subtitle (`text.weak`), count pill,
  `keyboard_arrow_down` chevron.
- Actions: circle Add project + capsule New session, shared height **52**,
  horizontal gap **8**, fill-only `text.strong` at **0.14** on **both**
  platforms (including iOS 26 — dock CTAs intentionally do not use per-button
  Liquid Glass so they match Android weight and alignment).
- Error: `color.error` + body-secondary text.

## Behavior

Stateless. Default filter chrome is a horizontal Filter Chip scroller: tap
selects; selected project chips may expose remove. When `useProjectPicker` is
on, a single scope control shows the selected tab (letter or All mark, name,
compact path or project-count subtitle, session count, chevron). Tap opens the
caller-owned **Filter by project** sheet. Horizontal drag does not switch
projects; it only rubber-bands the current identity cluster.

Swipe motion lives only in the identity cluster (mark + title/subtitle +
count). The control background, chevron, and dock CTAs stay pinned.

- Drag: the cluster is tethered. It can be pulled left or right, but it lags
  the finger like rubber — glued to rest, and the farther the pull the less
  extra travel you get, capping around **52pt**. Finger-up eases home over
  **180ms** with a slow takeoff and no bounce. Selection never changes.
- Interrupt: a new drag cancels an in-flight return and continues from the
  current offset.
- Viewport: clip to the cluster slot so a rubber-band pull cannot draw over
  the chevron. Do not paint an edge fade — at rest it dirties the letter mark.

VoiceOver / TalkBack treat the control as a button that opens the project
filter. They do not expose increment/decrement to switch projects.

Empty `filterTabs` omits the filter chrome (no empty strip). Add project is a
compact leading control; New session is the full-width primary control beside
it. Create error appears above the filter chrome when set.

Chip-mode removal uses the selected chip’s remove control. Picker-mode removal
is hold-to-remove on project rows in the sheet, not on the dock.

## Platform Notes

- iOS: `OpenCodeFloatingDock` + fill-only CTA row. Add project / New session
  stay fill-only (circle + capsule, 52pt) to match Android.
- Android: same structure with Material raised dock and fill-only CTAs at 52dp /
  8dp gap.

## Review Surfaces

Both Component Galleries include a **Session List Dock** entry with a **Picker**
preview switch (chips vs scope control), a **1 project** sample (All absent),
and an optional create-error toggle. With Picker on, drag the scope control to
feel the rubber-band tether; it does not change the selected project. Session
List gallery also shows the dock in full home context.

## Parity And Divergence

Shared layout and callbacks. Glass vs raised materials follow Floating Dock
divergence.
