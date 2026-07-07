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
- Shared intent: outgoing prompt cards should render as a stable trailing
  column that visually matches the `outgoingMessage.maxWidthFraction` width
  intent without shifting when new messages are appended.
- iOS behavior: `OpenCodeOutgoingMessage` uses a fixed leading gutter to produce
  the trailing column. This avoids SwiftUI `containerRelativeFrame` remeasurement
  during the send/auto-scroll update, which could shift the visible message-list
  padding.
- Android behavior: `OpenCodeOutgoingMessage` uses the shared
  `outgoingMessage.maxWidthFraction` value directly with Compose layout
  modifiers.
- Reason: the shared fractional width semantics remain visually aligned, but
  SwiftUI's relative container measurement was unstable in the session timeline
  after an optimistic send.
- Review trigger: revisit if the iOS timeline stops using lazy scroll
  relayout/auto-scroll, or if a stable shared row-width primitive replaces the
  current platform-specific measurement.

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
