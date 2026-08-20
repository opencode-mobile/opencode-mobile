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
  On uses `onOpenProjectPicker()` for tap on the scope control, and
  `onFilterSelected(id)` when a horizontal swipe commits the previous or next
  tab.
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
caller-owned **Filter by project** sheet. Horizontal swipe pages the previous
or next tab in `filterTabs` order without opening the sheet.

Swipe motion lives only in the identity cluster (mark + title/subtitle +
count). The control background, chevron, and dock CTAs stay pinned. The cluster
tracks the finger with light resistance; swipe left reveals the next tab from
the right. Commit at **36pt** or a flick of **800pt/s**; otherwise snap back.
Settle with the same **0.22s** smooth used for chip scroll-to-selected. Ends
rubber-band and do not wrap. Rapid swipes interrupt; they do not queue. Light
haptic on commit only. One tab still rubber-bands and never commits. VoiceOver
/ TalkBack expose increment and decrement on the control.

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
and an optional create-error toggle. With Picker on and more than one tab,
swipe the scope control to page projects. Session List gallery also shows the
dock in full home context.

## Parity And Divergence

Shared layout and callbacks. Glass vs raised materials follow Floating Dock
divergence.
