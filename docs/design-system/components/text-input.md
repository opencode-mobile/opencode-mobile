# Text Input

## Intent

`OpenCodeTextInput` is the shared single-line form control for text entry.
Feature screens use it for ordinary text, URLs, usernames, and passwords instead
of styling native text fields locally.

## Public API

- Variants: `text`, `url`, `username`, `password`.
- States: empty, populated, focused, disabled, and error.
- Required content: value binding/callback.
- Optional content: visible label, placeholder, accessibility label, error
  message, enabled state, and semantic submit action (`next` or `done`).

When the visible label is omitted, the component removes both the label and its
spacing. The caller must provide an accessibility label; placeholder copy does
not replace the semantic name.

The variant selects secure entry, capitalization, autocorrection, keyboard, and
content-type behavior. Visual styling is not exposed for feature-level override.

## Token Usage

- Semantic tokens: `surface-raised-base`, `text-strong`, `text-base`,
  `text-weaker`, `border-weaker-base`, `interactive`, and `error`.
- Component tokens: `textInput.cornerRadius`, `textInput.minHeight`,
  `textInput.paddingHorizontal`, and `textInput.labelSpacing`.
- Typography roles: `type.caption` for labels and messages; `type.body` for input.

## Behavior

- The entire field container activates the native text-entry control.
- Focus uses the interactive border; errors use the error border and message.
- Disabled fields remain readable but do not accept input.
- Password values use native secure-entry behavior and are not revealed by the
  component.
- Supplied labels remain visible when values or placeholders are empty.
- `next` advances to the next focusable field; `done` clears field focus and
  dismisses the keyboard according to native behavior.

## Platform Notes

- iOS: SwiftUI `TextField`/`SecureField` provide native content types and submit
  behavior.
- Android: Material `OutlinedTextField` provides native focus, keyboard, and
  password transformation behavior.

## Previews

Both Component Galleries provide one live field with controls for input kind,
visible-label presence, enabled state, and error state. The shared theme control
covers light and dark.

## Completion Ledger

- Public API and applicable states: complete.
- Token and typography mapping: complete; unchanged.
- iOS and Android implementations: complete.
- Gallery entries and previews: complete.
- Parity and divergences: complete; none known.
- Automated checks: covered by platform compilation and the feature checks that
  consume the unlabeled variant.

## Parity And Divergence

Native caret, selection, autofill, keyboard, and focus animations intentionally
follow each platform. There is no visual-contract divergence.
