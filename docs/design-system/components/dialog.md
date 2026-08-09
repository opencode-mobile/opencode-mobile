# Dialog

## Intent

Provide a reusable centered popup dialog for short confirmations and single-field
edits that should interrupt the current screen. Use it when the task needs a
title, exactly two choices, and optionally one line of supporting copy or a
single text field.

Prefer `OpenCodeBottomSheet` for multi-entry action lists. Prefer
`OpenCodeTopBarOverflowHost` for anchored secondary actions.

## Public API

- Variants: `confirmation` (no text field) and `form` (optional text field
  present). The variant is implied by whether text-field content is supplied.
- States:
  - dialog: presented, dismissed.
  - options: enabled, disabled.
  - text field: empty, populated, focused, disabled, error (delegated to
    `OpenCodeTextInput` when present).
  - loading: not applicable; callers disable options while a submit is in flight.
  - selected: not applicable.
- Required content:
  - `title`
  - primary option (`title`, action; optional `enabled`, optional `destructive`)
  - secondary option (`title`, action; optional `enabled`)
- Optional content:
  - `subtitle` supporting copy under the title
  - single `OpenCodeTextInput` configuration (hint or accessibility label,
    value binding, kind, enabled, error message)

Options are ordered secondary then primary in the action row (for example Cancel
then Save). Primary uses high-contrast button treatment by default; when
`destructive` is true on the primary option, primary maps to
`OpenCodeButton` contrast `destructive` (error-emphasized confirm). Secondary
uses low-contrast treatment. Both map to `OpenCodeButton` `fullWidthText` and
share the row equally.

Primary option API: `title`, `action`, optional `enabled` (default true),
optional `destructive` (default false). Use `destructive` for irreversible or
hard-to-undo confirms such as Remove / Delete / Clear.

## Token Usage

- Semantic tokens: `surface-base`, `text-strong`, `text-base`, and the shared
  scrim treatment used by modal overlays (`bottomSheet.scrim.alpha` until a
  dedicated modal scrim token exists).
- Component tokens: `dialog.cornerRadius`, `dialog.maxWidth`,
  `dialog.screenPaddingHorizontal`, `dialog.paddingHorizontal`,
  `dialog.paddingVertical`, `dialog.contentSpacing`,
  `dialog.titleSubtitleSpacing`, `dialog.optionSpacing`, and
  `dialog.shadowElevation`.
- Typography roles:
  - `type.title` for the dialog title.
  - `type.bodySecondary` for the subtitle.
  - text field roles remain those of `OpenCodeTextInput`.
  - option labels use `type.button` through `OpenCodeButton`.

## Behavior

The component owns modal presentation, scrim, and dismissal. Scrim tap, system
back, and accessibility dismiss call the dismiss request. Callers should treat
dismiss as the secondary path unless a submit is in flight; while submitting,
callers disable both options and ignore dismiss requests.

Primary and secondary options fire only when enabled. The component does not
auto-dismiss after an option tap; the caller clears presentation state.

When a text field is present, the dialog keeps the field reachable above the
keyboard. Submit from the keyboard should not invent a third action; callers may
wire the field's done action to the primary option.

## Platform Notes

- iOS: presented as a window-level overlay (same host pattern as the overflow
  menu) with a fade in/out, not a sliding cover. Keyboard safe-area insets lift
  the card so the field stays visible.
- Android: presented with Compose `Dialog` and a custom elevated card so chrome
  matches design-system tokens rather than raw Material `AlertDialog` defaults.
  `imePadding()` keeps the card above the IME when the text field is focused.

## Review Surfaces

- iOS Component Gallery: Dialog entry with controls for subtitle presence, text
  field presence, option enabled state, and destructive primary.
- Android Component Gallery: Dialog entry with the same controls.

## Completion Ledger

- Public API and applicable states: complete.
- Token and typography mapping: complete.
- iOS and Android implementations: complete.
- Gallery entries and previews: complete.
- Parity and divergences: complete; presentation primitive difference recorded
  in `../divergences.md`.
- Automated checks: not applicable for this component version beyond platform
  build of the gallery surfaces.

## Parity And Divergence

Shared semantics for title, optional subtitle, two options, and optional text
field are aligned across iOS and Android. Native modal presentation differences
are documented in `../divergences.md`.
