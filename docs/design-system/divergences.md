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

No other intentional design-system divergences have been recorded yet.
