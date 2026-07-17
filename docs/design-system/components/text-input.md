# Text Input

## Intent

`OpenCodeTextInput` is the shared single-line form control for text entry.
Feature screens use it for ordinary text, URLs, usernames, and passwords instead
of styling native text fields locally.

## Public API

- Variants: `text`, `url`, `username`, `password`.
- States: empty, populated, focused, disabled, and error.
- Required content: value binding/callback.
- Optional content: hint (in-field empty-state copy), accessibility label, error
  message, enabled state, and semantic submit action (`next` or `done`).

There is no external label above the field. The hint appears inside the field
only while the value is empty. Callers must provide a non-blank hint or a
non-blank accessibility label so the control has an accessible name; when both
are supplied, the accessibility label wins for semantics and the hint remains
the visible empty-state copy.

The variant selects secure entry, capitalization, autocorrection, keyboard, and
content-type behavior. Visual styling is not exposed for feature-level override.

## Token Usage

- Semantic tokens: `surface-raised-base`, `text-strong`, `text-base`,
  `text-weaker`, `border-weaker-base`, `interactive`, and `error`.
- Component tokens: `textInput.cornerRadius`, `textInput.minHeight`, and
  `textInput.paddingHorizontal`.
- Typography roles: `type.caption` for error messages; `type.body` for input and
  hint.

## Behavior

- The entire field container activates the native text-entry control.
- Focus uses the interactive border; errors use the error border and message.
- Disabled fields remain readable but do not accept input.
- Password values use native secure-entry behavior and are not revealed by the
  component.
- The hint is visible only when the value is empty; it does not float or move
  outside the field.
- `next` advances to the next focusable field; `done` clears field focus and
  dismisses the keyboard according to native behavior.

## Platform Notes

- iOS: SwiftUI `TextField`/`SecureField` provide native content types and submit
  behavior. Hint maps to the field's placeholder string.
- Android: Material `OutlinedTextField` provides native focus, keyboard, and
  password transformation behavior. Hint maps to the field's placeholder; no
  Material floating label is used.

## Previews

Both Component Galleries provide one live field with controls for input kind,
enabled state, error state, and empty vs populated value so the in-field hint
can be reviewed. The shared theme control covers light and dark.

## Completion Ledger

- Public API and applicable states: complete.
- Token and typography mapping: complete; `textInput.labelSpacing` removed with
  the external label.
- iOS and Android implementations: complete. Both enforce a non-blank hint or
  accessibility label, and neither renders an external label.
- Gallery entries and previews: complete.
- Parity and divergences: complete; none known.
- Automated checks: complete. Both platforms cover accessibility-label
  precedence, hint fallback, and rejection of empty unlabeled configurations.

## Parity And Divergence

Native caret, selection, autofill, keyboard, and focus animations intentionally
follow each platform. There is no visual-contract divergence.
