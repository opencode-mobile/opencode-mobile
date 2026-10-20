# Bottom Sheet

## Intent

Provide a reusable modal sheet for contextual choices and short forms that
should stay near the user's current screen. It has a clear title, an explicit
close affordance, a scrolling body, and optional parts pinned above and below
that body.

## Public API

- `height`: `fitContent` (default) or `half`.
  - `fitContent` wraps the header, pinned parts, and body while they fit the
    compact limit (`bottomSheet.compactMaxHeight`, 420). Taller content
    switches to the half-height detent. A compact sheet follows its body as it
    grows or shrinks; once a presentation reaches half height it stays there
    until `height` changes.
  - `half` rests at half the window height (less the top inset) from the first
    frame.
- `keyboard`: `dismiss` (default), `keep`, or `expand`.
  - `dismiss` clears focus and hides the software keyboard on present and
    whenever the sheet switches to this policy while presented.
  - `keep` leaves the keyboard up; the sheet keeps its resting height and rides
    above it.
  - `expand` leaves the keyboard up and, while it is visible, grows the sheet to
    fill the space between the top system inset and the keyboard. It never
    shrinks below the resting height. Header and pinned parts stay fixed; the
    body scrolls in the rest.
- Slots:
  - `pinnedTop`: fixed under the header, laid out by the caller (filter
    fields, a section header that should not scroll).
  - `content`: the scrolling body.
  - `pinnedBottom`: the footer. Never scrolled, kept above the keyboard and the
    navigation bar or home indicator. The sheet supplies its horizontal inset,
    the gap above it, and the clearance below it. Primary actions go here, not
    at the end of the body.
- Header: required `title` (a heading for assistive technology), a Close button,
  and an optional leading header action (an icon button before the title for
  in-sheet navigation such as returning to a parent level).
- `scrollTargetID` (iOS) / `scrollTargetKey` (Android): scrolls the body so the
  row marked with the same identity (`.id(_:)` on iOS,
  `Modifier.openCodeBottomSheetScrollTarget(key)` on Android) is centered once
  the sheet is scrollable, and again when the identity changes. Used by Recent
  Window and the model picker to open on the current choice.
- Content inset: free-form content in the body or `pinnedTop` (text, fields,
  filters) takes `openCodeBottomSheetContentInset()`, the same 20 logical px as
  the header, entries, and footer. Entries and list rows lay out their own inset.
  Callers do not add bottom padding: the sheet owns the clearance under the last
  row.

### Entry

`OpenCodeBottomSheetEntry` is a tappable row:

- `title` (required), wrapping to two lines at large text sizes.
- `enabled` (default true): disabled entries reduce emphasis and block
  interaction.
- `destructive` (default false): title and icon use the semantic error color.
- `selected` (default false): marks the current choice in a picker. Shows a
  check in the end slot (replacing the end decorator) and exposes the selected
  state to VoiceOver and TalkBack.
- Start and end decorators: `none` or `icon`. Decorators are decorative; the
  entry title is the row's label.

Use `OpenCodeListItem` when a row needs subtitle content or a larger list
treatment.

## Token Usage

- Semantic tokens: `surface-base` for the card, `text-strong` for the title and
  entry titles, `icon-strong-base` for enabled entry icons, `text-weaker` for
  disabled entry titles and icons, `error` for destructive entries,
  `surface-raised-base` for the pressed entry highlight.
- Component tokens: see the Bottom sheet table in `../tokens.md`.
- Typography roles: `type.heading` for the sheet title (Android
  `titleMedium`); `type.body` for entry titles (Android `bodyLarge`).

## Behavior

### Presentation and dismissal

The sheet is controlled by the caller (`isPresented` on iOS, `visible` on
Android). A dismiss gesture (scrim tap, Close, back, hardware Escape, or a
header drag) clears that state at once: the iOS binding becomes false and the
Android `onDismissRequest` is called immediately. The sheet then plays its exit
motion on its own, so a caller can present another sheet or navigate straight
from the dismiss without waiting. While it exits, the sheet keeps showing the
last content it had while presented, even if the caller cleared the state the
body reads (a null target, an empty list).

On Android, keep the sheet in composition and drive `visible`. Removing it from
composition (an `if` around the call) skips the exit motion.

Both platforms use one motion: the card slides up by its own height and fades
in over `bottomSheet.motion.enterDuration` (320 ms, decelerate
`cubic-bezier(0.4, 0, 0.2, 1)`), and slides down and fades out over
`bottomSheet.motion.exitDuration` (280 ms, accelerate
`cubic-bezier(0.4, 0, 1, 1)`). The scrim fades to `bottomSheet.scrim.alpha`
with the same timing.

The header follows a downward drag. Releasing past
`bottomSheet.drag.dismissThreshold` (96) or faster than
`bottomSheet.drag.dismissVelocity` (1200 per second) dismisses, and the exit
continues from the dragged position. Otherwise the card springs back. Drag is
limited to the header so body scrolling is not blocked.

Back (Android system back, including Escape from a hardware keyboard, which
Android falls back to Back) and hardware Escape on iOS dismiss. VoiceOver's
escape gesture dismisses on iOS. iPadOS has no system back gesture for an
overlay; Escape and the scrim cover it.

### Layout

The sheet is a concentric float: **8** logical px from the left, right, and
bottom of the display on **compact** width. Bottom corners use
`bottomSheet.cornerRadius` so they share a center with the device curve. Top
corners use the smaller `bottomSheet.cornerRadiusTop` so the header is not
eaten by the device radius. The home indicator or gesture handle sits on the
sheet. Side and bottom gutters show the dimmed presenting screen.

On **regular** width the card is centered, still 8 px from the bottom, and
capped at `layout.sheetMaxWidth`. All four corners use
`bottomSheet.cornerRadiusTop` because the card no longer shares a center with
the display curve.

The component owns vertical scrolling. Body content does not add its own
vertical scroll container; use `pinnedTop` for controls that must stay put.

While a sheet is presented, the presenting screen ignores keyboard-driven
resizing so the background stays stable.

### Accessibility

The overlay is accessibility-modal: VoiceOver and TalkBack stay on the sheet and
cannot reach the dimmed presenter. The title is a heading. The Close button has
a localized `Close` label. The scrim is not an accessibility element; Close,
back, and the escape gesture dismiss for assistive input. Entries are buttons,
and a `selected` entry exposes the selected state.

When provided, the leading header action performs caller-owned in-sheet
navigation and does not dismiss the sheet unless the caller does so. Give it a
clear label such as `Back to providers`.

Enabled entries use a spring press scale (~0.97) with a soft raised highlight.

## Platform Notes

- iOS: presented as a window overlay (same pattern as Dialog) so the card can
  inset from the display edge without system sheet chrome. The hosting view is
  pinned to the window edges, reports no safe-area regions, is
  accessibility-modal, and is removed when the host leaves the tree. The
  hosting controller owns the Escape key command; it takes focus on present
  unless another text input still holds it (a sheet that keeps the composer
  keyboard), in which case Escape works once focus moves into the sheet.
  Keeping sheets seed keyboard overlap from the last known keyboard frame, then
  follow `keyboardWillChangeFrame`.
- Android: presented as an activity-window overlay so the card can sit 8dp from
  the display edge, with system-bar and display-cutout insets consumed. Sibling
  decor views are hidden from TalkBack while it is attached. The body is
  composed once in every height mode: it always sits in the scroll container,
  which measures it at its natural height. Three-button navigation is detected
  from the tappable bottom inset (`WindowInsets.tappableElement`); it adds the
  navigation-bar height under the 8dp gap so the buttons stay uncovered, while
  gesture navigation keeps the handle on the card. `keep` applies `imePadding()`
  to the sheet boundary; `expand` lifts by the keyboard height and sizes the
  column from layout so keyboard frames do not recompose the body.

## Previews

- Light, dark, and default: included.
- Pressed: covered through platform interaction behavior.
- Disabled and selected entries: included.
- Loading and error: not applicable for this component.
- Decorators: start icon, end icon, and both positions are included.
- Header leading action, pinned top, pinned bottom, and each height and keyboard
  policy: included in the Component Gallery.

## Parity And Divergence

Height, keyboard policies, slots, insets, the compact limit, motion, drag
thresholds, the dismiss contract, tokens, and accessibility are shared across
iOS and Android. How each platform hosts the overlay, follows the keyboard, and
reads the display corner radius is documented in `../divergences.md`.
