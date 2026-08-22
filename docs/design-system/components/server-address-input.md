# Server Address Input

## Intent

`OpenCodeServerAddressInput` is the shared control for entering an opencode
server host as **hostname or IP + port**, with an **http/https** scheme prefix.
Use it on connection setup instead of a free-form URL text field when the product
wants scheme selection and paste-friendly host entry.

## Public API

- Variants: none (single control). Scheme is `http` or `https` (default
  `http`).
- States: empty, populated, focused (host field), disabled, error. Loading and
  selected are not applicable.
- Required content: full base-URL value binding/callback (for example
  `http://127.0.0.1:4096/` or `https://macbook.local:4096/`). The control owns
  scheme + host display and emits a normalized full URL with a trailing slash
  when the host is non-empty.
- Optional content: host-field hint (default `Host`), accessibility label, error
  message, enabled state, and semantic submit action (`next` or `done`) for the
  host field.

## Token Usage

- Semantic tokens: `surface-raised-base`, `text-strong`, `text-base`,
  `text-weaker`, `border-weaker-base`, `interactive`, `error`, and `icon-default`.
- Component tokens: reuse `textInput.cornerRadius`, `textInput.minHeight`, and
  `textInput.paddingHorizontal`.
- Typography roles: `type.body` for scheme label and host text/hint;
  `type.caption` for error messages.

## Behavior

- Layout matches a phone-style prefix field: tappable scheme segment (`http` /
  `https` + disclosure), vertical divider, then a single-line host field.
- Outer chrome (min height, corner radius, fill, border, body typography, error
  caption) matches `OpenCodeTextInput` so both fields read as the same control
  family.
- Scheme segment is full field height with a press/ripple indication over the
  whole hit target. Horizontal rhythm is
  `[16dp [scheme label · 4dp · 16dp disclosure] 8dp] 8dp [host] 16dp`. The
  bracketed segment is the scheme hit target; the following `8dp` is host leading
  inset.
- Tapping the scheme segment opens an `OpenCodeBottomSheet` with plain `http`
  and `https` rows only — no icons and no selected-state decoration. Choosing one
  updates the scheme and keeps the current host.
- The host field accepts hostname and address characters: letters, digits, `.`,
  `-`, `_`, `:`, and IPv6 extras `[` / `]`. Other characters are stripped as the
  user types.
- Pasting a full URL such as `https://macbook.local:8080/path` detects `http` or
  `https`, sets the scheme, strips scheme/path/query/fragment, filters the host
  to allowed characters, and updates the emitted base URL.
- Focus/error border treatment matches `OpenCodeTextInput` (interactive or error
  stroke on the shared container).
- Disabled: scheme tap and host editing are inactive.
- Accessibility: scheme control announces the current scheme and that it opens a
  chooser; host field uses the hint or accessibility label as its name.

## Platform Notes

- iOS: SwiftUI composed field matching `OpenCodeTextInput` chrome; scheme chooser
  via `openCodeBottomSheet`.
- Android: Compose composed field matching `OpenCodeTextInput` chrome and height;
  scheme chooser via `OpenCodeBottomSheet`. Scheme press uses Material ripple over
  the full-height hit target.
- Native keyboard, caret, and paste chrome stay platform-owned. Host keyboard is
  URL/ASCII-oriented; no autocorrect or capitalization.

## Review Surfaces

- Android Component Gallery entry **Server Address Input** (includes a sibling
  `OpenCodeTextInput` for height/style comparison).
- iOS Component Gallery entry **Server Address Input** (same comparison).
- Previews: idle with default `http` + sample host, https selected, empty host,
  error; side-by-side with a text input.

## Completion Ledger

- Public API and applicable states: complete.
- Token and typography mapping: complete; reuses `textInput.*` tokens.
- iOS and Android implementations: complete.
- Gallery entries and previews: complete.
- Parity and divergences: complete; none known.
- Automated checks: complete. Parsing/filter coverage on both platforms.

## Parity And Divergence

Native caret, keyboard, and paste chrome intentionally follow each platform.
Android composes matching chrome and a full-height Material ripple on the scheme
segment; iOS composes matching chrome with a full-height pressed highlight. There
is no visual-contract divergence.
