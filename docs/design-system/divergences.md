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

## List item start decorator icon identifier

- Status: intentional
- Area: component | accessibility
- Shared intent: A **24×24** leading icon in `OpenCodeListItem` that follows list
  emphasis (including disabled) and keeps feature copy out of raw implementation
  details.
- iOS behavior: `StartDecorator.icon(systemName:accessibilityLabel:)` uses an SF
  Symbol name (`String`) with an optional accessibility label.
- Android behavior: `StartDecorator.Icon(imageVector:contentDescription:)` uses a
  Compose `ImageVector` (typically Material Icons) with an optional Android
  content description.
- Reason: Each platform already has a native, type-safe icon selection model;
  forcing a single shared identifier string would fight tooling and provide no
  meaningful cross-platform coupling at this layer.
- Review trigger: If we introduce a shared catalog of semantic icon kinds (for
  example “folder”, “warning”) mapped per platform, revisit whether the public
  API should expose those kinds instead of raw identifiers.

No other intentional design-system divergences have been recorded yet.
