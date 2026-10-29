# Design System Tokens

This document is the canonical planning source for design-system tokens before
native app implementation exists.

The token system has four levels:

1. Primitive palette (seed colors from the bundled theme JSON)
2. Base semantic tokens (resolved from palette seeds via the theme resolver)
3. Component tokens
4. Platform mappings

## Theme JSON Format

The canonical theme source is a JSON file bundled with each app:

```json
{
  "name": "Porcelain",
  "id": "porcelain",
  "light": {
    "palette": { "neutral": "#F8F8F8", "ink": "#171717", ... },
    "overrides": { "text-strong": "#171717", ... }
  },
  "dark": {
    "palette": { "neutral": "#121212", "ink": "#EDEDED", ... },
    "overrides": { "text-strong": "#EDEDED", ... }
  }
}
```

Both apps load this at runtime, run the resolver, and produce the resolved token
dictionary. Only bundled presets follow this pipeline.

### Bundled theme presets

Five original themes ship with each app: Porcelain, Linen, Signal, Hotline,
and Phosphor. Canonical JSON lives in `shared/theme-presets/`; platform
bundles are updated via `npm run themes:sync` and verified with
`npm run themes:check` (see `scripts/sync-theme-presets.mjs`).

| Preset | Role |
| --- | --- |
| `porcelain` | Near-white / near-black default. Quiet blue taps. |
| `linen` | Quiet warm paper / espresso. Dusty olive taps. |
| `signal` | True white / true black. Electric blue is the shout. |
| `hotline` | Neutral chrome, loud pink on taps and user bubbles. |
| `phosphor` | Neutral chrome, CRT green on taps and user bubbles. |

Assistant Message and Tool Call Card have no hairline. Every preset must
keep `surface-raised-base` a different luminance from `background-base` in
both modes so those cards stay visible on Session Chat.

| Platform | Bundle location |
| --- | --- |
| iOS | `Resources/ThemePresets/` (may flatten into bundle root at build time) |
| Android | `assets/theme-presets/` |

`manifest.json` lists `{ id, name, blurb }` pairs for the Settings theme picker.
Default selection is the `porcelain` preset unless the user chooses another
bundled preset. Users still on a retired preset ID fall back to Porcelain.

### Syntax highlight presets

Code-fence colors are a separate setting from the UI theme. Canonical JSON
lives in `shared/syntax-presets/`; platform bundles are updated by the same
`npm run themes:sync` command.

| Preset | Role |
| --- | --- |
| `match` | Virtual. Use the active UI theme’s own `syntax-*` tokens. Default. |
| `nocturne` | One Dark family |
| `citrus` | Solarized family |
| `paper` | GitHub family |
| `neon` | Dracula family |

`match` has no JSON file. The other presets overlay only `syntax-*` keys onto
the resolved UI theme. The syntax catalog is `syntax-manifest.json` so it does
not collide with the theme `manifest.json` when iOS flattens resource folders.
Settings → Appearance opens dedicated preview pages for both pickers (card
gallery), not a name-only sheet.

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
Tables below show Porcelain, the default preset.

### Background & Surface

| Token | Porcelain Light | Porcelain Dark |
|---|---|---|
| `background-base` | `#F8F8F8` | `#121212` |
| `surface-base` | `#F8F8F8` | `#121212` |
| `surface-base-hover` | `#0000000A` | `#FFFFFF0D` |
| `surface-raised-base` | `#FFFFFF` | `#1C1C1C` |
| `surface-raised-base-hover` | resolved from `neutral` | resolved from `neutral` |
| `surface-interactive-weak` | `#E8EEFF` | `#161C2E` |
| `surface-success-base` | `#E3F5EA` | `#12261A` |
| `surface-critical-base` | `#FCECEC` | `#2A1414` |

### Text

| Token | Porcelain Light | Porcelain Dark |
|---|---|---|
| `text-strong` | `#171717` | `#EDEDED` |
| `text-base` | `#6F6F6F` | `#A0A0A0` |
| `text-weak` | `#8F8F8F` | `#707070` |
| `text-weaker` | `#C7C7C7` | `#505050` |
| `text-interactive-base` | `#1456F7` | `#C0D4FB` |
| `text-on-brand-base` | resolved from `primary` seed | resolved from `primary` seed |
| `icon-invert-base` | `#FFFFFF` | `#121212` |

### Border

| Token | Porcelain Light | Porcelain Dark |
|---|---|---|
| `border-weak-base` | `#E5E5E5` | `#282828` |
| `border-weaker-base` | `#F0F0F0` | `#232323` |

### Icon

| Token | Porcelain Light | Porcelain Dark |
|---|---|---|
| `icon-base` | `#8F8F8F` | `#7E7E7E` |
| `icon-weak-base` | resolved from `ink` / `neutral` | resolved from `ink` / `neutral` |
| `icon-strong-base` | `#171717` | `#EDEDED` |

### Button

| Token | Porcelain Light | Porcelain Dark |
|---|---|---|
| `button-primary-base` | `#171717` | `#EDEDED` |
| `button-secondary-base` | resolved from `neutral` | resolved from `neutral` |
| `button-secondary-hover` | resolved from `neutral` | resolved from `neutral` |

### Semantic Status

| Token | Porcelain Light | Porcelain Dark |
|---|---|---|
| `interactive` | `#1456F7` | `#8BA8FF` |
| `success` | `#089B00` | `#4EC97A` |
| `warning` | `#C78B05` | `#E0B04A` |
| `error` | `#CB321C` | `#E85A5A` |
| `info` | `#5A5A8A` | `#B4A6E0` |
| `diff-add` | `#089B00` | `#7AE0A0` |
| `diff-delete` | `#CB321C` | `#E85A5A` |

### Syntax (code highlighting)

| Token | Porcelain Light | Porcelain Dark |
|---|---|---|
| `syntax-comment` | `#8F8F8F` | `#707070` |
| `syntax-keyword` | `#1456F7` | `#8BA8FF` |
| `syntax-string` | `#087466` | `#4EC9B8` |
| `syntax-number` | `#D8119C` | `#7AD4E0` |
| `syntax-constant` | `#D8119C` | `#7AD4E0` |
| `syntax-type` | `#8A6508` | `#E0B04A` |
| `syntax-function` | `#0B8A0B` | `#3CEF3C` |
| `syntax-property` | `#5A5A8A` | `#C4B08A` |
| `syntax-attribute` | `#852743` | `#F045F0` |
| `syntax-variable` | `#CB321C` | `#E06C75` |
| `syntax-operator` | `#3A7A82` | `#56B6C2` |
| `syntax-punctuation` | `#171717` | `#EDEDED` |
| `syntax-critical` | resolved from `primary` | resolved from `primary` |
| `syntax-diff-delete` | resolved from `primary` | resolved from `primary` |
| `syntax-diff-unknown` | resolved from `info` | resolved from `info` |

A code color preset (`shared/syntax-presets/`) replaces these values and may
add `syntax-background` and `syntax-foreground`, the editor background and
plain code text of the editor theme it comes from. Match theme keeps the UI
theme's values and leaves both out.

### Syntax role mapping (code blocks)

Every code surface (chat code fences, tool results, the File Viewer, settings
previews, and the Component Gallery) maps shared `CodeTokenRole` values through
one table, `CodeRoleTokens` on Android and `OpenCodeCodeColors` on iOS:

| `CodeTokenRole` | Syntax token |
|---|---|
| `Keyword`, `Tag` | `syntax-keyword` |
| `String` | `syntax-string` |
| `Number` | `syntax-number` |
| `Constant` | `syntax-constant` |
| `Comment` | `syntax-comment` |
| `Type` | `syntax-type` |
| `Function` | `syntax-function` |
| `Property` | `syntax-property` |
| `Attribute` | `syntax-attribute` |
| `Variable`, `Operator`, `Punctuation`, `Plain` | plain code text: `syntax-foreground`, else `text-strong` |
| `Inserted` | `diff-add` |
| `Deleted` | `diff-delete` |
| `Warning` | `syntax-diff-unknown` |
| `Critical` | `syntax-critical` |

Variables, operators, and punctuation stay plain, as in most editor themes, so
the colored roles stand out. Code blocks draw on `syntax-background` when the
preset has one, else on the surface's own background.

### Markdown (rendered markdown colors)

| Token | Purpose |
|---|---|
| `markdown-h1` | Heading 1 text |
| `markdown-h2` | Heading 2 text |
| `markdown-h3` | Heading 3 text |
| `markdown-link-text` | Link text (body strong; not interactive accent) |
| `markdown-link-underline` | Link underline (matches link text) |
| `markdown-code-text` | Inline code text |
| `markdown-code-bg` | Inline code background (ink/white at ~8% alpha, matching desktop `color-mix` tint) |
| `markdown-quote-text` | Blockquote text |
| `markdown-quote-bar` | Blockquote bar (muted border; not interactive accent) |
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

### Layout

Shared page and overlay width. **Compact** is a phone-width window. **Regular**
is everything wider. Top bars stay edge-to-edge. Screens center a column at
`layout.contentMaxWidth`. Camera viewfinders stay full-bleed. Bottom sheets stay
edge-inset on compact and become a max-width card on regular.

| Token | Value |
| --- | --- |
| `layout.contentMaxWidth` | 720 (logical px) |
| `layout.sheetMaxWidth` | 560 (logical px) |
| `layout.compactMaxWidth` | 600 (logical px; Android container-width breakpoint. iOS screens use `horizontalSizeClass`; window overlays use this same 600 px check) |

`OpenCodeLayoutWidth.compact` | `regular` is the only public width class. Do
not expose Material medium/expanded or raw breakpoints on feature APIs.

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
| `listItem.statusDotSize` | 6 (logical px) |
| `listItem.statusDotSpacing` | 4 (logical px) |

### List item start decorator (letter badge)

The letter variant of `OpenCodeListItem` uses a fixed **36×36** badge with a
small corner radius (`listItem.startDecorator.letter.cornerRadius`, **6**)
derived from these pairs. Do not use these tokens outside the list-item letter
badge without revisiting the design-system docs.

| Token | Light | Dark |
| --- | --- | --- |
| `listItem.startDecorator.letter.size` | 36 (logical px) | 36 (logical px) |
| `listItem.startDecorator.letter.cornerRadius` | 6 (logical px) | 6 (logical px) |
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

Interactive pill used for gallery controls, Session Composer model / mode /
thinking-effort pickers (glass variant), and similar compact selectors.
Standard geometry is shared across platforms; glass variant uses capsule
geometry aligned with Filter Chip. Fills map to semantic surfaces as documented
in `docs/design-system/components/choice-badge.md`.

| Token | Value |
| --- | --- |
| `choiceBadge.cornerRadius` | 16 (logical px; standard variant) |
| `choiceBadge.minHeight` | 32 (logical px; standard; glass uses 36) |
| `choiceBadge.paddingHorizontal` | 12 (logical px) |
| `choiceBadge.paddingVertical` | 8 (logical px) |
| `choiceBadge.glass.cornerRadius` | capsule (fully rounded) |
| `choiceBadge.glass.minHeight` | 36 (logical px) |

### Filter tabs

Horizontal single-select filter row with optional count badges (for example
Session List project filters). Geometry is shared across platforms; colors map
to semantic surfaces as documented in
`docs/design-system/components/filter-tabs.md`.

| Token | Value |
| --- | --- |
| `filterTabs.tab.paddingHorizontal` | 12 (logical px) |
| `filterTabs.tab.paddingVertical` | 8 (logical px) |
| `filterTabs.tab.minHeight` | 36 (logical px) |
| `filterTabs.tab.spacing` | 8 (logical px between tabs) |
| `filterTabs.tab.contentSpacing` | 6 (logical px between label and count) |
| `filterTabs.count.minSize` | 22 (logical px) |
| `filterTabs.count.paddingHorizontal` | 6 (logical px) |
| `filterTabs.row.paddingHorizontal` | 16 (logical px) |
| `filterTabs.row.paddingVertical` | 8 (logical px) |
| `filterTabs.tab.borderWidth` | 1.5 (logical px; selected capsule stroke) |

### Filter chip

Single capsule filter used inside docks and related chrome. Geometry aligns with
filter-tabs density; remove is optional.

| Token | Value |
| --- | --- |
| `filterChip.metaSize` | 20 (logical px; count pill min height and remove circle) |
| `filterChip.remove.iconInset` | 4 (logical px; padding inside remove circle on both platforms) |

### Action pill

Compact floating pill for contextual overlay actions such as scroll-to-end in
chat. Geometry is shared across platforms. Materials follow the floating glass
capsule family (`OpenCodeFloatingPillChrome` / Android floating-chrome stand-in)
as documented in `docs/design-system/components/action-pill.md`.

| Token | Value |
| --- | --- |
| `actionPill.minHeight` | 36 (logical px; enforced via vertical padding) |
| `actionPill.paddingHorizontal` | 14 (logical px) |
| `actionPill.paddingVertical` | 8 (logical px) |
| `actionPill.iconSize` | 16 (logical px) |
| `actionPill.iconSpacing` | 6 (logical px) |
| `actionPill.shadowElevation` | 4 (logical px; Android / pre–iOS 26 fallback only) |

### Syncing pill

Non-interactive floating status pill for quiet cache→network sync over the
timeline. Reuses Action pill geometry; materials follow the same floating glass
capsule family as documented in `docs/design-system/components/syncing-pill.md`.

| Token | Value |
| --- | --- |
| `syncingPill.minHeight` | 36 (logical px; same as `actionPill.minHeight`) |
| `syncingPill.paddingHorizontal` | 14 (logical px) |
| `syncingPill.paddingVertical` | 8 (logical px) |
| `syncingPill.spinnerSize` | 14 (logical px) |
| `syncingPill.iconSpacing` | 6 (logical px) |
| `syncingPill.shadowElevation` | 4 (logical px; Android / pre–iOS 26 fallback only) |

### Review changes pill

Contextual floating entry point for reviewing the latest completed turn's
changes. It is a separate component from Action pill even though both use the
same floating glass capsule geometry.

| Token | Value |
| --- | --- |
| `reviewChangesPill.minHeight` | 36 (logical px; enforced via vertical padding) |
| `reviewChangesPill.paddingHorizontal` | 14 (logical px) |
| `reviewChangesPill.paddingVertical` | 8 (logical px) |
| `reviewChangesPill.shadowElevation` | 4 (logical px; Android / pre–iOS 26 fallback only) |

### Session todos pill

Contextual floating entry point for the live Session Todo List. Shares the
floating glass capsule language with Action / Review Changes pills and adds a
max width so the active preview can truncate.

| Token | Value |
| --- | --- |
| `sessionTodosPill.minHeight` | 36 (logical px; enforced via vertical padding) |
| `sessionTodosPill.paddingHorizontal` | 14 (logical px) |
| `sessionTodosPill.paddingVertical` | 8 (logical px) |
| `sessionTodosPill.shadowElevation` | 4 (logical px; Android / pre–iOS 26 fallback only) |
| `sessionTodosPill.statusDotSize` | 8 (logical px) |
| `sessionTodosPill.contentSpacing` | 8 (logical px) |
| `sessionTodosPill.maxWidth` | 320 (logical px) |

### Session todo item

View-only Session Todo row used in the Session Todos sheet (and any future
Session Todo List surfaces). Status mark geometry is shared; callers own outer
list insets when embedding in a sheet.

| Token | Value |
| --- | --- |
| `sessionTodoItem.mark.size` | 18 (logical px) |
| `sessionTodoItem.mark.cornerRadius` | 4 (logical px) |
| `sessionTodoItem.mark.borderWidth` | 1.5 (logical px) |
| `sessionTodoItem.mark.iconSize` | 12 (logical px) |
| `sessionTodoItem.mark.indeterminateWidth` | 8 (logical px) |
| `sessionTodoItem.mark.indeterminateHeight` | 2 (logical px) |
| `sessionTodoItem.mark.fillOpacity` | 0.15 |
| `sessionTodoItem.contentSpacing` | 12 (logical px) |
| `sessionTodoItem.paddingHorizontal` | 20 (logical px) |
| `sessionTodoItem.paddingVertical` | 10 (logical px) |
| `sessionTodoItem.pendingContentOpacity` | 0.85 |

### Hunk navigation pill

Bottom-centered hunk and context control for read-only change review. It shares
the raised capsule language with the other floating pills and gives all three
actions an equal native touch target.

| Token | Value |
| --- | --- |
| `hunkNavigationPill.width` | 132 (logical px) |
| `hunkNavigationPill.minHeight` | 44 (logical px) |
| `hunkNavigationPill.iconButtonSize` | 44 (logical px) |
| `hunkNavigationPill.iconSize` | 20 (logical px) |
| `hunkNavigationPill.shadowElevation` | 4 (logical px) |

### List group

One bordered and clipped surface for an ordered collection of List Item rows.

| Token | Value |
| --- | --- |
| `listGroup.cornerRadius` | 12 (logical px) |

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
without competing with primary actions. Accent contrast uses the `interactive`
fill with `text-on-brand-base` content so a single CTA follows the theme brand.
Disabled accent uses an opaque **0.10** mix of `interactive` over
`surface-raised-base`, with `text-weak` content — not `surface-interactive-weak`
(that token is the outgoing-message wash and stays loud on Hotline / Phosphor).

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

### Session composer

Persistent Session Chat interaction surface that switches between prompt entry
and permission approval without changing its ownership boundary. Shell fill uses
shared Floating Chrome (`surface.raised` / platform glass or raised Surface),
not opaque `surface-base`.

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

### Composer suggestion overlay

Composer-anchored `/` and `@` suggestion panel above Session Chat composer.

| Token | Value |
| --- | --- |
| `composerSuggestionOverlay.cornerRadius` | 12 (logical px) |
| `composerSuggestionOverlay.visibleItemLimit` | 4 |
| `composerSuggestionOverlay.rowPaddingVertical` | 10 (logical px each side) |
| `composerSuggestionOverlay.rowInternalSpacing` | 2 (logical px) |

Max list height = `visibleItemLimit` × estimated subtitle-row height (title +
description line heights + paddings/spacing). Fewer than 4 rows hug content;
beyond 4 rows the list scrolls inside the panel.

### Top bar

Reusable page-level navigation bar with an optional leading navigation affordance,
single-line title, and compact trailing actions.

| Token | Value |
| --- | --- |
| `topBar.minHeight` | 56 (logical px) |
| `topBar.paddingHorizontal` | 12 (logical px) |
| `topBar.paddingVertical` | 12 (logical px) |
| `topBar.itemSpacing` | 8 (logical px) |
| `topBar.titleSpacing` | 12 (logical px) after a leading control |
| `topBar.titleEdgeInsetWhenNoLeading` | 18 (logical px) from screen edge to title when leading is `none` |
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

### Dialog

Centered popup dialog with a title, optional subtitle, exactly two options, and
an optional single-line text field.

| Token | Value |
| --- | --- |
| `dialog.cornerRadius` | 16 (logical px) |
| `dialog.maxWidth` | 360 (logical px) |
| `dialog.screenPaddingHorizontal` | 24 (logical px) |
| `dialog.paddingHorizontal` | 20 (logical px) |
| `dialog.paddingVertical` | 20 (logical px) |
| `dialog.contentSpacing` | 16 (logical px) |
| `dialog.titleSubtitleSpacing` | 8 (logical px) |
| `dialog.optionSpacing` | 8 (logical px) |
| `dialog.shadowElevation` | 8 (logical px) |

### Bottom sheet

Reusable modal action sheet with a title, close affordance, and compact action
entries.

| Token | Value |
| --- | --- |
| `bottomSheet.inset` | 8 (logical px; left, right, and bottom from the display edge) |
| `bottomSheet.maxWidth` | `layout.sheetMaxWidth` (regular only; compact stays edge-inset) |
| `bottomSheet.displayCornerRadiusFallback` | 55 (logical px; used only when the platform cannot read the display corner) |
| `bottomSheet.cornerRadiusTop` | 24 (logical px; top corners only — not the device curve) |
| `bottomSheet.cornerRadius` | display corner radius − `bottomSheet.inset` (bottom corners only; 47 with the fallback) |
| `bottomSheet.header.paddingHorizontal` | 20 (logical px) |
| `bottomSheet.header.paddingTop` | 12 (logical px) |
| `bottomSheet.header.paddingBottom` | 8 (logical px) |
| `bottomSheet.content.paddingBottom` | 20 (logical px) |
| `bottomSheet.entry.minHeight` | 48 (logical px) |
| `bottomSheet.entry.paddingHorizontal` | 20 (logical px) |
| `bottomSheet.entry.paddingVertical` | 12 (logical px) |
| `bottomSheet.entry.decorator.size` | 24 (logical px) |
| `bottomSheet.entry.decorator.iconSize` | 20 (logical px) |
| `bottomSheet.motion.enterDuration` | 320 ms, `cubic-bezier(0.4, 0, 0.2, 1)` |
| `bottomSheet.motion.exitDuration` | 280 ms, `cubic-bezier(0.4, 0, 1, 1)` |
| `bottomSheet.scrim.alpha` | 0.32 |
| `bottomSheet.drag.dismissThreshold` | 96 (logical px) |
| `bottomSheet.drag.dismissVelocity` | 1200 (logical px per second, downward release) |
| `bottomSheet.compactMaxHeight` | 420 (logical px; tallest `fitContent` sheet that still wraps its content) |
| `bottomSheet.halfHeight` | 50% of the window height, less `bottomSheet.inset` |
| `bottomSheet.content.paddingHorizontal` | 20 (logical px; free-form body and pinned-top content, header, entries, and footer) |
| `bottomSheet.footer.paddingTop` | 8 (logical px; gap above `pinnedBottom`) |

### Action tile

Labeled icon tile for compact action palettes such as the Commands sheet.

| Token | Value |
| --- | --- |
| `actionTile.cornerRadius` | 14 (logical px) |
| `actionTile.minHeight` | 88 (logical px) |
| `actionTile.paddingTop` | 18 (logical px) |
| `actionTile.paddingHorizontal` | 14 (logical px) |
| `actionTile.paddingBottom` | 16 (logical px) |
| `actionTile.iconSize` | 24 (logical px) |
| `actionTile.contentGap` | 12 (logical px) |
| `actionTile.grid.gap` | 10 (logical px) |
| `actionTile.grid.paddingHorizontal` | 16 (logical px) |
| `actionTile.grid.minColumnWidth` | 160 (logical px) |
| `actionTile.grid.minColumns` | 2 |
| `actionTile.grid.maxColumns` | 4 |

### Snackbar

Short auto-dismissing feedback overlay for transient success or error copy.

| Token | Value |
| --- | --- |
| `snackbar.cornerRadius` | 8 (logical px) |
| `snackbar.paddingHorizontal` | 16 (logical px) |
| `snackbar.paddingVertical` | 12 (logical px) |
| `snackbar.maxWidth` | 560 (logical px) |
| `snackbar.screenMarginHorizontal` | 16 (logical px) |
| `snackbar.screenMarginBottom` | 16 (logical px) |
| `snackbar.autoDismissDuration` | 4000 ms |
| `snackbar.shadowElevation` | 4 (logical px / Material elevation) |

### Outgoing message

Reusable outgoing prompt card for session conversations. Fill is
`surface-interactive-weak` so the user voice reads apart from assistant
cards. No hairline — the wash is the edge.

| Token | Value |
| --- | --- |
| `outgoingMessage.cornerRadius` | 8 (logical px; matches `listItem.cornerRadius`) |
| `outgoingMessage.maxWidthFraction` | 0.80 of the available row width |
| `outgoingMessage.paddingHorizontal` | 12 (logical px) |
| `outgoingMessage.paddingVertical` | 8 (logical px) |
| `outgoingMessage.footerSpacing` | 6 (logical px) |

### Assistant message

Reusable leading-aligned assistant reply card for session conversations.
Fill is `surface-raised-base`. No hairline — luminance against
`background-base` is the edge. Do not share the outgoing wash.

| Token | Value |
| --- | --- |
| `assistantMessage.cornerRadius` | 8 (logical px; matches `listItem.cornerRadius`) |
| `assistantMessage.maxWidth` | 720 (logical px; same cap as `layout.contentMaxWidth`) |
| `assistantMessage.paddingHorizontal` | 12 (logical px) |
| `assistantMessage.paddingVertical` | 8 (logical px) |

### Tool call card

Collapsed, tool-agnostic assistant tool-call row. Same raised fill as
Assistant Message, no hairline. Status chrome reuses the shared
`success` / `warning` / `error` semantic tokens; +/− counts reuse
`diffAdd` / `diffDelete`.

| Token | Value |
| --- | --- |
| `toolCallCard.cornerRadius` | 8 (logical px; matches `listItem.cornerRadius`) |
| `toolCallCard.paddingHorizontal` | 12 (logical px) |
| `toolCallCard.paddingVertical` | 10 (logical px) |
| `toolCallCard.statusDotSize` | 6 (logical px) |
| `toolCallCard.statusDotSpacing` | 10 (logical px) |
| `toolCallCard.statusDotTopPadding` | 6 (logical px) |
| `toolCallCard.contentSpacing` | 4 (logical px) |
| `toolCallCard.metaRowSpacing` | 8 (logical px) |

### Child session row

Quiet person/thread row for a child session. Reuses shared surface, text, and
icon semantic tokens. Do not use List Item `LetterAccent` colors.

| Token | Value |
| --- | --- |
| `childSessionRow.cornerRadius` | 8 (logical px; matches `toolCallCard.cornerRadius`) |
| `childSessionRow.paddingHorizontal` | 12 (logical px) |
| `childSessionRow.paddingVertical` | 10 (logical px) |
| `childSessionRow.avatarSize` | 32 (logical px) |
| `childSessionRow.avatarGlyphSize` | 14 (logical px; bold rounded) |
| `childSessionRow.contentSpacing` | 12 (logical px) |
| `childSessionRow.titleRowSpacing` | 8 (logical px) |
| `childSessionRow.textStackSpacing` | 2 (logical px) |
| `childSessionRow.trailingSpacing` | 6 (logical px) |
| `childSessionRow.chevronSize` | 16 (logical px) |

## Platform Mappings

Platform mappings translate semantic and component tokens into native APIs:

- iOS: SwiftUI colors through `OpenCodeColors` + `ThemeManager`.
- Android: Material 3 color roles + custom tokens through `ThemeResolver`.
- Width class: iOS screens use `horizontalSizeClass` (`compact` / `regular`).
  Android uses container width against `layout.compactMaxWidth` (same split as
  `WindowWidthSizeClass.Compact` vs Medium/Expanded). Bottom-sheet window
  overlays on both platforms use the 600 px container-width check so Split
  View and multi-window match the visible pane.

Mappings may differ when platform conventions require it. Record intentional
differences in `docs/design-system/divergences.md`.

## Public API Rules

- Public component APIs should use semantic variants and explicit states.
- Do not expose arbitrary hex colors, one-off gradients, raw radius values, or
ad-hoc typography overrides as public customization.
- When a new token changes component behavior or appearance, update both
platform samples and previews in the same change once app projects exist.
