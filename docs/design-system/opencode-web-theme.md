# OpenCode Web Theme Reference

This document records the color and typography evidence extracted from the
OpenCode web UI running locally on May 4, 2026. Treat these values as source
material for the mobile design system, not as public component API names.

The live DOM extraction was captured from the active dark theme. The shipped CSS
also includes light theme values, so both themes are recorded below.

## Extraction Source

- Page: `http://127.0.0.1:4096/.../session`
- Stylesheet: `/assets/index-Jk3uGALE.css`
- DOM coverage: 155 visible elements
- CSS coverage: 1,035 computed root variables and 1,120 stylesheet variables

## Light Theme Evidence

These are the main light-mode semantic CSS variables found in the stylesheet.

| Web variable | Value |
| --- | --- |
| `--background-base` | `#f8f8f8` |
| `--background-weak` | `#f3f3f3` |
| `--background-strong` | `#fcfcfc` |
| `--background-stronger` | `#fcfcfc` |
| `--surface-base` | `#00000008` |
| `--surface-raised-base` | `#00000008` |
| `--text-strong` | `#171717` |
| `--text-base` | `#6f6f6f` |
| `--text-weak` | `#8f8f8f` |
| `--text-weaker` | `#c7c7c7` |
| `--text-interactive-base` | `#034cff` |
| `--border-base` | `#00000029` |
| `--border-weak-base` | `#e5e5e5` |
| `--border-weaker-base` | `#f0f0f0` |
| `--icon-base` | `#8f8f8f` |
| `--icon-strong-base` | `#171717` |

## Dark Theme Evidence

The CSS contains multiple dark root blocks. The current computed page values are
the best reference for the visible product theme.

| Web variable | Computed value |
| --- | --- |
| `--background-base` | `#121212` |
| `--background-weak` | `#191919` |
| `--background-strong` | `#121212` |
| `--background-stronger` | `#151515` |
| `--surface-base` | `#1C1C1C` |
| `--surface-weak` | `#282727` |
| `--surface-inset-base` | `#202020` |
| `--surface-raised-base` | `#282828` |
| `--surface-float-base` | `#151515` |
| `--text-strong` | `#EDEDED` |
| `--text-base` | `#A0A0A0` |
| `--text-weak` | `#707070` |
| `--text-weaker` | `#505050` |
| `--text-interactive-base` | `#c0d4fb` |
| `--border-base` | `#4d4c4a` |
| `--border-weak-base` | `#282828` |
| `--border-weaker-base` | `#232323` |
| `--icon-base` | `#7E7E7E` |
| `--icon-strong-base` | `#ede8e4` |

## Status And Accent Evidence

These values appeared in the dark theme variables and visible DOM sample.

| Intent | Web evidence |
| --- | --- |
| Interactive accent | `#1456f7` |
| Interactive text | `#c0d4fb` |
| Success | `#089b00` |
| Warning | `#c78b05` |
| Critical | `#cb321c` |
| Info | `#cd73d5` |
| Syntax property | `#fab283` |
| Syntax type | `#fcd53a` |
| Syntax string | `#00ceb9` |

## Mobile Semantic Token Seed

Use these as the first candidate mappings in `tokens.md` when platform tokens
are implemented.

| Mobile token | Light seed | Dark seed |
| --- | --- | --- |
| `color.background.primary` | `#f8f8f8` | `#121212` |
| `color.background.secondary` | `#f3f3f3` | `#191919` |
| `color.background.elevated` | `#fcfcfc` | `#151515` |
| `color.surface.primary` | `#ffffff` | `#1C1C1C` |
| `color.surface.secondary` | `#f3f3f3` | `#282727` |
| `color.surface.inset` | `#f0f0f0` | `#202020` |
| `color.text.primary` | `#171717` | `#EDEDED` |
| `color.text.secondary` | `#6f6f6f` | `#A0A0A0` |
| `color.text.tertiary` | `#8f8f8f` | `#707070` |
| `color.text.disabled` | `#c7c7c7` | `#505050` |
| `color.text.interactive` | `#034cff` | `#c0d4fb` |
| `color.border.subtle` | `#e5e5e5` | `#282828` |
| `color.border.default` | `#00000029` | `#4d4c4a` |
| `color.icon.default` | `#8f8f8f` | `#7E7E7E` |
| `color.icon.strong` | `#171717` | `#ede8e4` |
| `color.status.success` | `#12c905` | `#089b00` |
| `color.status.warning` | `#fbdd46` | `#c78b05` |
| `color.status.error` | `#fc533a` | `#cb321c` |
| `color.status.info` | `#a753ae` | `#cd73d5` |

Alpha-heavy web colors such as `#ffffff08` and `#00000029` should become
platform-appropriate overlays or border roles instead of raw public tokens.

## Typography Evidence

The web UI uses system font stacks. Mobile should keep the product font intent
from `typography.md`, using this extraction as fallback and sizing evidence.

| Web variable | Value |
| --- | --- |
| `--font-family-sans` | `ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif` |
| `--font-family-mono` | `ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace` |
| `--font-size-small` | `13px` |
| `--font-size-base` | `14px` |
| `--font-size-large` | `16px` |
| `--font-size-x-large` | `20px` |
| `--font-weight-regular` | `400` |
| `--font-weight-medium` | `500` |
| `--line-height-normal` | `130%` |
| `--line-height-large` | `150%` |
| `--line-height-x-large` | `180%` |
| `--letter-spacing-normal` | `0` |
| `--letter-spacing-tight` | `-.16` |

Observed DOM typography was mostly `13px` regular or medium UI text, with `14px`
for prompt input/body content and `20px` medium for the empty-state title.

## Implementation Notes

- Keep feature and component APIs semantic, for example `color.text.primary`,
  not `--text-strong`.
- Use the light and dark seeds together when adding platform previews.
- Preserve native readability where exact web sizes feel too small on mobile.
- Record any intentional iOS or Android differences in `divergences.md`.
