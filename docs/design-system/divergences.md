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

## Letter badge colors

- Status: intentional
- Area: token
- Shared intent: `OpenCodeListItem` start-decorator letter badges use the same 5
  accent pairs (violet, teal, amber, blue, rose) on both platforms.
- iOS behavior: `LetterAccent` enum exposes `badgeFill` and `badgeTint` as
  hardcoded light/dark color pairs in `OpenCodeColors`.
- Android behavior: `LetterAccent` enum exposes `badgeColors(useDarkTheme)` with
  the same hardcoded light/dark color pairs in `Color.kt`.
- Reason: the 5 letter-badge accent pairs have no equivalent in the OpenCode
  desktop theme JSON schema. They are component-level tokens, not derived from
  palette seeds, and are not expected to change when a custom theme is imported.
  Hardcoding them avoids unnecessary resolver complexity.
- Review trigger: if a future theme JSON schema adds letter-badge accent seeds,
  migrate these to the resolver.

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

## Outgoing message width measurement

- Status: intentional
- Area: component
- Shared intent: outgoing prompt cards are trailing aligned, shrink to content
  width, and cap at `outgoingMessage.maxWidthFraction` (80% of the row).
- iOS behavior: `OpenCodeOutgoingMessage` uses a trailing `Layout` that caps
  content at `outgoingMessage.maxWidthFraction` (80% of the row) while content
  drives row height. The layout reader does not wrap the message body.
- Android behavior: `OpenCodeOutgoingMessage` uses `BoxWithConstraints` with
  `widthIn(max = …)` on the bubble column and content so the same max-width cap
  applies without a minimum width.
- Reason: short prompts should not stretch to the full outgoing column; only
  long wrapped content should expand toward the shared max-width intent.
- Review trigger: revisit if a shared row-width primitive replaces the current
  platform-specific max-width measurement.

## Session chat scroll-to-end on iOS

- Status: intentional
- Area: component | motion
- Shared intent: show the scroll-to-end action pill when the timeline is not
  pinned to the bottom; scroll to the latest messages on tap.
- iOS behavior: Session Chat temporarily uses eager SwiftUI `VStack` timeline
  layout and `ScrollViewReader.scrollTo(lastMessage, anchor: .bottom)`.
- Android behavior: Session Chat uses lazy Compose list state and scrolls to the
  bottom anchor item.
- Reason: SwiftUI `LazyVStack` programmatic scrolling is unreliable for
  variable-height markdown chat rows; it can miss the target or land in blank
  estimated space. Eager layout trades long-history performance for correct
  scroll-to-end behavior while the durable iOS timeline is rebuilt.
- Review trigger: replace the iOS SwiftUI scroll view with a UIKit-backed
  `UICollectionView`/`UITableView` timeline using hosted SwiftUI cells before
  treating long-history chat performance as production-ready.

## Overflow menu presentation

- Status: intentional
- Area: component | accessibility
- Shared intent: `OpenCodeOverflowMenu` exposes the same entry model, enabled
  state, opaque elevated panel chrome, and dismissal behavior for compact
  top-bar overflow actions with a leading icon and single-line label.
- iOS behavior: presented with a styled popover anchored to the trigger supplied
  through the component label closure. `OpenCodeOverflowMenuTrigger` provides the
  standard `moreVert` button for top-bar placement.
- Android behavior: presented with a styled window popup aligned to the
  top-trailing overflow-button anchor. The overflow icon in `OpenCodeTopBar`
  toggles an explicit `expanded` state.
- Reason: each platform uses its native popup primitive for anchoring and
  dismissal while sharing OpenCode surface, border, radius, and entry layout.
- Review trigger: revisit if product design requires a single cross-platform
  popup implementation or if a shared overflow trigger API is added directly to
  `OpenCodeTopBar`.

## Bottom sheet form keyboard presentation

- Status: intentional
- Area: component | motion
- Shared intent: the presenting screen stays fixed when the keyboard appears;
  only the form sheet's available region changes, with a fixed header and a
  scrollable body that keeps focused controls reachable.
- iOS behavior: SwiftUI's native sheet uses a stable 560pt detent in `form` mode
  and lets the keyboard safe area resize the internal scroll region.
- Android behavior: the full-window popup remains over an `adjustNothing`
  activity; `imePadding()` is applied to the form sheet boundary so it follows
  animated IME insets without resizing the activity content.
- Reason: native presentation and inset delivery differ, but both implementations
  preserve the same visible ownership boundary and interaction behavior.
- Review trigger: revisit if both platforms adopt a shared native modal API with
  equivalent detent and keyboard-inset semantics.
