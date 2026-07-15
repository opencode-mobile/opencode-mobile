# Design System Tokens

This document is the canonical planning source for design-system tokens before
native app implementation exists.

The token system has four levels:

1. Primitive palette (seed colors from the OC-2 theme JSON)
2. Base semantic tokens (resolved from palette seeds via the theme resolver)
3. Component tokens
4. Platform mappings

## Theme JSON Format

The canonical theme source is an OC-2 format JSON file bundled with each app:

```json
{
  "$schema": "https://opencode.ai/desktop-theme.json",
  "name": "OC-2",
  "id": "oc-2",
  "light": {
    "palette": { "neutral": "#f7f7f7", "ink": "#171311", ... },
    "overrides": { "text-strong": "#171717", ... }
  },
  "dark": {
    "palette": { "neutral": "#1f1f1f", "ink": "#f1ece8", ... },
    "overrides": { "text-strong": "#EDEDED", ... }
  }
}
```

Both apps load this at runtime, run the resolver, and produce the resolved token
dictionary. Bundled presets and imported themes follow the same pipeline.

### Bundled theme presets

Thirty-seven desktop themes ship with each app. Canonical JSON lives in
`shared/theme-presets/`; platform bundles are updated via `npm run themes:sync`
and verified with `npm run themes:check` (see `scripts/sync-theme-presets.mjs`).

| Platform | Bundle location |
| --- | --- |
| iOS | `Resources/ThemePresets/` (may flatten into bundle root at build time) |
| Android | `assets/theme-presets/` |

`manifest.json` lists `{ id, name }` pairs for the Settings theme picker. Default
selection is the `oc-2` preset unless the user chooses another preset or imports
custom JSON.

## Palette Seeds

Palette seeds are the primitive input to the resolver. Every theme must provide
these for each mode:

| Seed | Purpose |
|---|---|
| `neutral` | Base color for backgrounds, surfaces, text hierarchy. Highest-impact seed. |
| `ink` | Tunes readable foreground/body tones alongside `neutral`. |
| `primary` | Brand seed. Generates brand surfaces, text-on-brand tokens. Not button fills. |
| `success` | Success surfaces, borders, icons, syntax success. |
| `warning` | Warning surfaces, borders, icons, syntax warning. |
| `error` | Critical/error surfaces, borders, icons, syntax critical. |
| `info` | Info surfaces, borders, icons, agent plan, syntax property/info. |
| `interactive` | Interaction state: focus, selection, links, active inputs. |
| `diffAdd` | Added diff surfaces, text, icons (optional; falls back to `success`). |
| `diffDelete` | Deleted diff surfaces, text, icons (optional; falls back to `error`). |

## Resolved Semantic Tokens

The resolver expands palette seeds into resolved tokens, then applies overrides.
The canonical tokens match the OpenCode desktop token schema:

### Background & Surface

| Token | OC-2 Light | OC-2 Dark |
|---|---|---|
| `background-base` | `#F8F8F8` | `#1C1C1C` |
| `surface-base` | `#F8F8F8` | `#1C1C1C` |
| `surface-base-hover` | `#0000000A` | `#FFFFFF0D` |
| `surface-raised-base` | `#F3F3F3` | `#232323` |
| `surface-raised-base-hover` | `#EDEDED` | `#282828` |
| `surface-interactive-weak` | `#F5FAFF` | `#0D172B` |
| `surface-success-base` | `#E6FFE5` | `#022B00` |
| `surface-critical-base` | `#FFF2F0` | `#1F0603` |

### Text

| Token | OC-2 Light | OC-2 Dark |
|---|---|---|
| `text-strong` | `#171717` | `#EDEDED` |
| `text-base` | `#6F6F6F` | `#A0A0A0` |
| `text-weak` | `#8F8F8F` | `#707070` |
| `text-weaker` | `#C7C7C7` | `#505050` |
| `text-interactive-base` | `#034CFF` | `#8CB0FF` |
| `text-on-brand-base` | resolved from `primary` seed | resolved from `primary` seed |
| `icon-invert-base` | `#FFFFFF` | `#1C1C1C` |

### Border

| Token | OC-2 Light | OC-2 Dark |
|---|---|---|
| `border-weak-base` | `#DBDBDB` | `#282828` |
| `border-weaker-base` | `#E8E8E8` | `#232323` |

### Icon

| Token | OC-2 Light | OC-2 Dark |
|---|---|---|
| `icon-base` | `#8F8F8F` | `#7E7E7E` |
| `icon-weak-base` | `#C7C7C7` | `#343434` |
| `icon-strong-base` | `#171717` | `#EDEDED` |

### Button

| Token | OC-2 Light | OC-2 Dark |
|---|---|---|
| `button-primary-base` | resolved from `ink` | resolved from `ink` |
| `button-secondary-base` | resolved from `neutral` | resolved from `neutral` |
| `button-secondary-hover` | resolved from `neutral` | resolved from `neutral` |

### Semantic Status

| Token | OC-2 Light | OC-2 Dark |
|---|---|---|
| `interactive` | `#034CFF` | `#034CFF` |
| `success` | `#12C905` | `#12C905` |
| `warning` | `#FFDC17` | `#FCD53A` |
| `error` | `#FC533A` | `#FC533A` |
| `info` | `#A753AE` | `#EDB2F1` |
| `diff-add` | `#9FF29A` | `#C8FFC4` |
| `diff-delete` | `#FC533A` | `#FC533A` |

### Syntax (code highlighting)

| Token | OC-2 Light | OC-2 Dark |
|---|---|---|
| `syntax-comment` | `#7A7A7A` | `#8F8F8F` |
| `syntax-keyword` | `#A753AE` | `#EDB2F1` |
| `syntax-string` | `#00CEB9` | `#00CEB9` |
| `syntax-primitive` | `#034CFF` | `#8CB0FF` |
| `syntax-property` | `#A753AE` | `#FAB283` |
| `syntax-type` | `#8A6F00` | `#FCD53A` |
| `syntax-constant` | `#007B80` | `#93E9F6` |
| `syntax-variable` | `#D1383D` | `#E06C75` |
| `syntax-operator` | `#318795` | `#56B6C2` |
| `syntax-punctuation` | `text-strong` | `text-strong` |
| `syntax-critical` | `#FF8C00` | `#FAB283` |
| `syntax-diff-delete` | `#FF8C00` | `#FAB283` |
| `syntax-diff-unknown` | `#A753AE` | `#EDB2F1` |

### Syntax role mapping (code blocks)

Rendered Markdown maps shared `CodeTokenRole` values to syntax tokens using the
same buckets as the web client:

| `CodeTokenRole` | Syntax token | Web appearance |
|---|---|---|
| `Comment` | `syntax-comment` | Gray |
| `Keyword` | `syntax-keyword` | Pink / magenta |
| `Type` | `syntax-type` | Yellow / gold class names |
| `Function` | `syntax-primitive` | Sky blue builtins (`Map`, `Object.entries`) |
| `Property`, `Constant` | `syntax-constant` | Cyan / teal members, keys, `this` |
| `String` | `syntax-string` | Mint / teal string literals |
| `Number`, `Attribute` | `syntax-property` | Peach / orange numeric literals |
| `Variable` | `syntax-variable` | Red / rose parameters and locals |
| `Operator` | `syntax-operator` | Teal operators |
| `Punctuation` | `syntax-punctuation` | Strong punctuation |
| `Plain` | `text-primary` | Off-white fallback text |
| `Tag` | `syntax-info` | Info accent |
| `Inserted` | `diff-add` | Diff added lines |
| `Deleted` | `diff-delete` | Diff deleted lines |
| `Warning` | `syntax-diff-unknown` | Diff headers / unknown |
| `Critical` | `syntax-critical` | Invalid / error emphasis |

Private field declarations (`#baseUrl;`) intentionally stay plain. Member access
(`this.#baseUrl`) uses `Property` and renders with `syntax-constant`. JavaScript
`this` and expression keywords such as `new` are normalized to web roles before
rendering.

### Markdown (rendered markdown colors)

| Token | Purpose |
|---|---|
| `markdown-h1` | Heading 1 text |
| `markdown-h2` | Heading 2 text |
| `markdown-h3` | Heading 3 text |
| `markdown-link-text` | Link text |
| `markdown-link-underline` | Link underline |
| `markdown-code-text` | Inline code text |
| `markdown-code-bg` | Inline code background |
| `markdown-quote-text` | Blockquote text |
| `markdown-quote-bar` | Blockquote bar |
| `markdown-list-marker` | List bullet/number |
| `markdown-image` | Image label/title |
| `markdown-hr` | Horizontal rule |
| `markdown-strong` | Bold text accent |
| `markdown-em` | Emphasized text accent |

## Component Tokens

Component tokens define reusable component-specific decisions derived from base
semantic tokens.

Examples:

- `button.primary.background`
- `button.primary.foreground`
- `button.primary.disabled.background`
- `sessionCard.active.border`
- `permissionBanner.warning.background`

Use component tokens when a component needs stable internal styling across
states, variants, or platforms.

### List item container

The outer list-row surface (`OpenCodeListItem`) uses one shared corner radius on
iOS and Android when the row owns a visible container. **`plain`** unselected
rows are transparent, borderless, and cornerless. **`card`** unselected rows use
`surface-base` plus a **1px** stroke from `border-weaker-base`.
Selected rows in either style use `surface-raised-base` with the shared
corner radius.

| Token | Value |
| --- | --- |
| `listItem.cornerRadius` | 8 (logical px) |

### List item start decorator (letter badge)

The letter variant of `OpenCodeListItem` uses a fixed **24×24** badge with a
small corner radius (`listItem.startDecorator.letter.cornerRadius`, **4**)
derived from these pairs. Do not use these tokens outside the list-item letter
badge without revisiting the design-system docs.

| Token | Light | Dark |
| --- | --- | --- |
| `listItem.startDecorator.letter.cornerRadius` | 4 (logical px) | 4 (logical px) |
| `listItem.startDecorator.letter.accent.violet.fill` | `#4c3d5c` | `#3a2f47` |
| `listItem.startDecorator.letter.accent.violet.tint` | `#e2d5f5` | `#d8c8ee` |
| `listItem.startDecorator.letter.accent.teal.fill` | `#2d4a42` | `#243832` |
| `listItem.startDecorator.letter.accent.teal.tint` | `#b8f5e0` | `#a8edd8` |
| `listItem.startDecorator.letter.accent.amber.fill` | `#5c3d28` | `#4a3224` |
| `listItem.startDecorator.letter.accent.amber.tint` | `#ffb86c` | `#ffaa55` |
| `listItem.startDecorator.letter.accent.blue.fill` | `#2a3a55` | `#1e2d42` |
| `listItem.startDecorator.letter.accent.blue.tint` | `#a8d4ff` | `#9ec8ff` |
| `listItem.startDecorator.letter.accent.rose.fill` | `#5c2d3d` | `#4a2533` |
| `listItem.startDecorator.letter.accent.rose.tint` | `#ffb8d4` | `#ffa8cc` |
| `listItem.startDecorator.letter.accent.red.fill` | `#5c2d2d` | `#4a2424` |
| `listItem.startDecorator.letter.accent.red.tint` | `#ffc8c8` | `#ffb8b8` |
| `listItem.startDecorator.letter.accent.green.fill` | `#2d4a3d` | `#243832` |
| `listItem.startDecorator.letter.accent.green.tint` | `#b8f5d8` | `#a8edc8` |
| `listItem.startDecorator.letter.accent.indigo.fill` | `#2d3d5c` | `#24304a` |
| `listItem.startDecorator.letter.accent.indigo.tint` | `#b8c8ff` | `#a8b8ff` |

### Choice badge

Interactive pill used for gallery controls and similar compact selectors.
Geometry is shared across platforms; fills map to semantic surfaces as
documented in `docs/design-system/components/choice-badge.md`.

| Token | Value |
| --- | --- |
| `choiceBadge.cornerRadius` | 16 (logical px; capsule when height is smaller) |
| `choiceBadge.minHeight` | 32 (logical px; enforced via vertical padding) |
| `choiceBadge.paddingHorizontal` | 12 (logical px) |
| `choiceBadge.paddingVertical` | 8 (logical px) |

### Action pill

Compact floating pill for contextual overlay actions such as scroll-to-end in
chat. Geometry is shared across platforms; fills map to raised semantic
surfaces as documented in `docs/design-system/components/action-pill.md`.

| Token | Value |
| --- | --- |
| `actionPill.minHeight` | 36 (logical px; enforced via vertical padding) |
| `actionPill.paddingHorizontal` | 14 (logical px) |
| `actionPill.paddingVertical` | 8 (logical px) |
| `actionPill.iconSize` | 16 (logical px) |
| `actionPill.iconSpacing` | 6 (logical px) |
| `actionPill.shadowElevation` | 4 (logical px) |

### Review changes pill

Contextual floating entry point for reviewing the latest completed turn's
changes. It is a separate component from Action pill even though both use the
same compact raised geometry.

| Token | Value |
| --- | --- |
| `reviewChangesPill.minHeight` | 36 (logical px; enforced via vertical padding) |
| `reviewChangesPill.paddingHorizontal` | 14 (logical px) |
| `reviewChangesPill.paddingVertical` | 8 (logical px) |
| `reviewChangesPill.shadowElevation` | 4 (logical px) |

### File navigation pill

Bottom-centered file stepping control for read-only change review. It shares
the raised capsule language with the other floating pills, while its larger
minimum height preserves a native touch target for each directional action.

| Token | Value |
| --- | --- |
| `fileNavigationPill.width` | 200 (logical px) |
| `fileNavigationPill.minHeight` | 44 (logical px) |
| `fileNavigationPill.iconButtonSize` | 44 (logical px) |
| `fileNavigationPill.iconSize` | 20 (logical px) |
| `fileNavigationPill.summaryPaddingHorizontal` | 12 (logical px) |
| `fileNavigationPill.shadowElevation` | 4 (logical px) |

### Unified diff

Read-only line-oriented diff surface with stable gutters and horizontally
scrollable source rows.

| Token | Value |
| --- | --- |
| `unifiedDiff.lineMinHeight` | 20 (logical px) |
| `unifiedDiff.linePaddingHorizontal` | 8 (logical px) |
| `unifiedDiff.markerWidth` | 20 (logical px) |
| `unifiedDiff.lineNumberGutterWidth` | 48 (logical px per old/new gutter pair) |
| `unifiedDiff.hunkSpacing` | 8 (logical px) |

### Button

Reusable action component with text and icon-only variants.

High contrast buttons invert against the surrounding page using semantic
foreground/background tokens. Low contrast buttons use secondary surface fill,
primary or strong content, and subtle border so secondary actions remain visible
without competing with primary actions.

| Token | Value |
| --- | --- |
| `button.cornerRadius` | 8 (logical px) |
| `button.minHeight.fullWidthText` | 48 (logical px) |
| `button.minHeight.compactText` | 40 (logical px) |
| `button.paddingHorizontal.text` | 14 (logical px) |
| `button.paddingVertical.text` | 10 (logical px) |
| `button.iconSquare.size` | 40 (logical px; width equals height) |
| `button.iconCircle.size` | 40 (logical px; width equals height) |
| `button.icon.size` | 18 (logical px) |

### Text input

Reusable labeled, single-line form control. Focus and error borders map to the
shared `interactive` and `error` semantic tokens.

| Token | Value |
| --- | --- |
| `textInput.cornerRadius` | 8 (logical px) |
| `textInput.minHeight` | 48 (logical px) |
| `textInput.paddingHorizontal` | 12 (logical px) |
| `textInput.labelSpacing` | 6 (logical px) |

### Session composer

Persistent Session Chat interaction surface that switches between prompt entry
and permission approval without changing its ownership boundary.

| Token | Value |
| --- | --- |
| `sessionComposer.cornerRadius` | 26 (logical px) |
| `sessionComposer.screenPaddingHorizontal` | 12 (logical px) |
| `sessionComposer.screenPaddingBottom` | 10 (logical px) |
| `sessionComposer.contentPaddingHorizontal` | 12 (logical px) |
| `sessionComposer.contentPaddingVertical` | 12 (logical px) |
| `sessionComposer.primaryContentInset` | 4 (logical px) |
| `sessionComposer.rowSpacing` | 10 (logical px) |
| `sessionComposer.actionSpacing` | 8 (logical px) |
| `sessionComposer.outlineWidth` | 1 (logical px) |
| `sessionComposer.motion.resizeDuration` | 240 ms |
| `sessionComposer.motion.outgoingFadeDuration` | 80 ms |
| `sessionComposer.motion.incomingFadeDelay` | 80 ms |
| `sessionComposer.motion.incomingFadeDuration` | 120 ms |

### Top bar

Reusable page-level navigation bar with an optional leading navigation affordance,
single-line title, and compact trailing actions.

| Token | Value |
| --- | --- |
| `topBar.minHeight` | 56 (logical px) |
| `topBar.paddingHorizontal` | 12 (logical px) |
| `topBar.paddingVertical` | 12 (logical px) |
| `topBar.itemSpacing` | 8 (logical px) |
| `topBar.titleSpacing` | 12 (logical px) |
| `topBar.titleBlockHeight` | 48 (logical px) |
| `topBar.subtitleSpacing` | 0 (logical px) |
| `topBar.dividerThickness` | 1 (logical px) |

Connection status label tokens used by `OpenCodeConnectionStatusLabel` and top-bar
`subtitleAccessory.statusDot`:

| Token | Value |
| --- | --- |
| `connectionStatusLabel.dotSize` | 6 (logical px) |
| `connectionStatusLabel.dotSpacing` | 4 (logical px) |

### Overflow menu

Compact anchored popup menu for secondary top-bar actions with icon and label
entries.

| Token | Value |
| --- | --- |
| `overflowMenu.cornerRadius` | 8 (logical px) |
| `overflowMenu.minWidth` | 200 (logical px) |
| `overflowMenu.paddingVertical` | 4 (logical px) |
| `overflowMenu.shadowElevation` | 4 (logical px) |
| `overflowMenu.anchor.paddingEnd` | 12 (logical px) |
| `overflowMenu.entry.minHeight` | 48 (logical px) |
| `overflowMenu.entry.paddingHorizontal` | 12 (logical px) |
| `overflowMenu.entry.iconSize` | 20 (logical px) |
| `overflowMenu.entry.iconSpacing` | 12 (logical px) |

### Bottom sheet

Reusable modal action sheet with a title, close affordance, and compact action
entries.

| Token | Value |
| --- | --- |
| `bottomSheet.cornerRadiusTop` | 24 (logical px) |
| `bottomSheet.header.paddingHorizontal` | 20 (logical px) |
| `bottomSheet.header.paddingTop` | 12 (logical px) |
| `bottomSheet.header.paddingBottom` | 8 (logical px) |
| `bottomSheet.content.paddingBottom` | 20 (logical px) |
| `bottomSheet.entry.minHeight` | 48 (logical px) |
| `bottomSheet.entry.paddingHorizontal` | 20 (logical px) |
| `bottomSheet.entry.paddingVertical` | 12 (logical px) |
| `bottomSheet.entry.decorator.size` | 24 (logical px) |
| `bottomSheet.entry.decorator.iconSize` | 20 (logical px) |
| `bottomSheet.motion.enterDuration` | 320 ms |
| `bottomSheet.motion.exitDuration` | 280 ms |
| `bottomSheet.scrim.alpha` | 0.32 |
| `bottomSheet.drag.dismissThreshold` | 96 (logical px) |
| `bottomSheet.formMaxHeight` | 560 (logical px; iOS content-measured form cap) |

### Outgoing message

Reusable outgoing prompt card for session conversations.

| Token | Value |
| --- | --- |
| `outgoingMessage.cornerRadius` | 8 (logical px; matches `listItem.cornerRadius`) |
| `outgoingMessage.maxWidthFraction` | 0.80 of the available row width |
| `outgoingMessage.paddingHorizontal` | 12 (logical px) |
| `outgoingMessage.paddingVertical` | 8 (logical px) |
| `outgoingMessage.footerSpacing` | 6 (logical px) |

## Platform Mappings

Platform mappings translate semantic and component tokens into native APIs:

- iOS: SwiftUI colors through `OpenCodeColors` + `ThemeManager`.
- Android: Material 3 color roles + custom tokens through `ThemeResolver`.

Mappings may differ when platform conventions require it. Record intentional
differences in `docs/design-system/divergences.md`.

## Public API Rules

- Public component APIs should use semantic variants and explicit states.
- Do not expose arbitrary hex colors, one-off gradients, raw radius values, or
ad-hoc typography overrides as public customization.
- When a new token changes component behavior or appearance, update both
platform samples and previews in the same change once app projects exist.
