# Design System Divergences

Use this document to record intentional design-system differences between iOS
and Android.

Do not use this file for unfinished work. Temporary mismatches during feature
implementation belong in the feature's `parity-gaps.md`.

## Divergence Template

```markdown
## <Short Name>

- Status: intentional
- Area: token | typography | component | motion | accessibility | other
- Shared intent:
- iOS behavior:
- Android behavior:
- Reason:
- Review trigger:
```

## Current Divergences

## Material icon platform types

- Status: intentional
- Area: component | accessibility
- Shared intent: both apps render the same Material glyph shapes from
  [`icons/manifest.yaml`](../../icons/manifest.yaml); see
  [`icons.md`](icons.md).
- iOS behavior: icon-bearing APIs use `OpenCodeIcon` (asset catalog name) and
  `OpenCodeIconImage` for template rendering.
- Android behavior: icon-bearing APIs use Compose `ImageVector` from
  `OpenCodeIcons`.
- Reason: each platform keeps a native, type-safe icon model while sharing one
  manifest-driven catalog and generated assets.
- Review trigger: if a semantic `OpenCodeIconKind` API replaces raw catalog
  symbols at component boundaries, revisit whether both platforms should expose
  only that kind.

## Top bar title alignment

- Status: intentional
- Area: component
- Shared intent: `OpenCodeTopBar` should read as one shared page-level
  navigation component with the same leading semantics, title contract, and
  trailing action model on both platforms.
- iOS behavior: title remains visually centered within the bar.
- Android behavior: title is aligned to the start edge after the leading action.
- Reason: centered iOS navigation titles and start-aligned Android app-bar
  titles both match established platform expectations better than forcing one
  cross-platform alignment rule.
- Review trigger: If product design later calls for a distinct branded app
  chrome that intentionally overrides native alignment expectations on both
  platforms, revisit this divergence.
