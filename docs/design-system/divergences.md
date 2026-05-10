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

## Native icon identifier inputs

- Status: intentional
- Area: component | accessibility
- Shared intent: icon-bearing component APIs should remain ergonomic and
  type-safe while preserving shared component semantics across platforms.
- iOS behavior: icon inputs use SF Symbol names (`String`) with optional
  accessibility labels (for example `StartDecorator.icon(...)` and
  `OpenCodeButton.Variant.iconSquare(...)` or
  `OpenCodeButton.Variant.iconCircle(...)`).
- Android behavior: icon inputs use Compose `ImageVector` values with optional
  content descriptions (for example `StartDecorator.Icon(...)` and
  `OpenCodeButtonVariant.IconSquare(...)` or
  `OpenCodeButtonVariant.IconCircle(...)`).
- Reason: Each platform already has a native, type-safe icon selection model;
  forcing a single shared identifier string would fight tooling and provide no
  meaningful cross-platform coupling at this layer.
- Review trigger: If we introduce a shared catalog of semantic icon kinds (for
  example “folder”, “warning”) mapped per platform, revisit whether the public
  API should expose those kinds instead of raw identifiers.

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
