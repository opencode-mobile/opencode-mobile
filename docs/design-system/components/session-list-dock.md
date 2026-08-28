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
  Optional `onNewSessionLongPress()`; omit when the hold path should not exist.
- Filter chrome: `useProjectPicker` (default off). Off uses Filter Chip
  `onFilterSelected(id)` plus optional selected-project `onRemoveProject(id)`.
  On uses `onOpenProjectPicker()` for tap on the scope control, and
  `onFilterSelected(id)` when a horizontal drag crosses the switch threshold.
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
caller-owned **Filter by project** sheet. Horizontal drag rubber-bands the
current identity cluster. If a neighbor exists in that direction and the
visual pull crosses the switch threshold, the control fires a canned switch
immediately — the finger does not need to lift, and it does not scrub the
transition. Once the switch starts, it runs to completion.

Swipe motion lives only in the identity cluster (mark + title/subtitle +
count). The control background, chevron, and dock CTAs stay pinned. The
chevron overlays the trailing edge of the identity slot so a stretch or
switch slides behind it; at rest the cluster keeps a trailing inset so the
count is not under the icon.

- Drag: the cluster is tethered. It can be pulled left or right, but it lags
  the finger like rubber — glued to rest, and the farther the pull the less
  extra travel you get, capping around **52pt**. Neighbor pages do not peek
  during the drag.
- Cross **28pt** of visual travel with a neighbor: selection haptic, select that
  tab, and play a **450ms** switch without waiting for finger-up. The current
  cluster continues off in the pull direction; the next cluster enters from
  the opposite side. Remaining motion on that finger-down is ignored until
  lift; switch again only after a new drag. Pull left selects the next tab;
  pull right selects the previous. Ends do not wrap.
- Lift under threshold, or no neighbor in that direction: ease home over
  **180ms** with a slow takeoff and no bounce. Selection does not change.
- Interrupt: a new drag cancels an in-flight rubber-band return and continues
  from the current offset. A switch in flight is not interruptible.
- Viewport: clip to the identity slot, including the area under the chevron.
  While dragging, returning, or switching, alpha-fade the identity **16pt**
  on both leading and trailing corners so sliding content dissolves. Fade
  width eases **0 ↔ 16pt** over **200ms**. Do not paint a color overlay.
  Idle has no fade so the letter mark stays clean.

VoiceOver / TalkBack treat the control as a button that opens the project
filter, and expose increment/decrement to switch projects when a neighbor
exists.

Empty `filterTabs` omits the filter chrome (no empty strip). Add project is a
compact leading control; New session is the full-width primary control beside
it. Create error appears above the filter chrome when set.

Tap New session fires `onNewSession()`. When `onNewSessionLongPress` is set,
hold New session plays a context-action haptic, fires that callback, and does
not fire `onNewSession`. Callers omit the hold callback in chip chrome and when
there are fewer than two projects. VoiceOver / TalkBack keep the default
**New session** action and expose **Choose project** when the hold path is
present. Accessibility actions do not play the hold haptic.

Chip-mode removal uses the selected chip’s remove control. Picker-mode removal
is hold-to-remove on project rows in the sheet, not on the dock.

## Platform Notes

- iOS: `OpenCodeFloatingDock` + fill-only CTA row. Add project / New session
  stay fill-only (circle + capsule, 52pt) to match Android. Play selection and
  context-action haptics through UIKit generators at the gesture.
  `.sensoryFeedback` is silent when a sheet presents in the same update.
- Android: same structure with Material raised dock and fill-only CTAs at 52dp /
  8dp gap. Project switch uses `HapticFeedbackType.TextHandleMove`; New session
  hold uses `LongPress`.

## Review Surfaces

Both Component Galleries include a **Session List Dock** entry with a **Picker**
preview switch (chips vs scope control), a **1 project** sample (All absent),
and an optional create-error toggle. With Picker on, drag the scope control to
feel the rubber-band tether; pull past the threshold and the switch fires
without lifting. Hold **New session** to open the New session sheet (omitted
when **1 project** is on). Session List gallery also shows the dock in full home
context.

## Parity And Divergence

Shared layout and callbacks. Glass vs raised materials follow Floating Dock
divergence.
