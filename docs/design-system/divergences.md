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

## `type.body` line density

- Status: intentional
- Area: typography
- Shared intent: Session Chat agent prose (plain assistant text and rendered
  markdown body) uses `type.body` at 16pt/sp so long replies are less dense than
  `type.bodySecondary` (14).
- iOS behavior: `OpenCodeTypography.body` stays 16pt with the platform default
  line height; Session Chat / `OpenCodeRenderedMarkdown` prose applies an extra
  `+4pt` line spacing only on those paths.
- Android behavior: `type.body` maps to Material `bodyLarge` with a global
  **26sp** line height (~1.6×), so every `bodyLarge` consumer inherits the
  looser leading.
- Reason: Android line height is owned by the type role mapping; iOS has no
  equivalent global body line-height token without changing `OpenCodeTypography`
  for all screens, so chat/markdown apply spacing locally. Shared contract and
  mapping table live in [`typography.md`](typography.md).
- Review trigger: if iOS gains a shared body line-height on `OpenCodeTypography`,
  or Android scopes 26sp leading to Session Chat only, revisit and collapse this
  divergence.

## Letter badge colors

- Status: intentional
- Area: token
- Shared intent: `OpenCodeListItem` start-decorator letter badges use the same 5
  accent pairs (violet, teal, amber, blue, rose) on both platforms.
- iOS behavior: `LetterAccent` enum exposes `badgeFill` and `badgeTint` as
  hardcoded light/dark color pairs in `OpenCodeColors`.
- Android behavior: `LetterAccent` enum exposes `badgeColors(useDarkTheme)` with
  the same hardcoded light/dark color pairs in `Color.kt`.
- Reason: the 5 letter-badge accent pairs have no equivalent in the theme JSON
  schema. They are component-level tokens, not derived from palette seeds, and
  are not expected to change when the selected theme changes. Hardcoding them
  avoids unnecessary resolver complexity.
- Review trigger: if a future theme JSON schema adds letter-badge accent seeds,
  migrate these to the resolver.

## Lucide icon platform types

- Status: intentional
- Area: component | accessibility
- Shared intent: both apps render the same Lucide glyph shapes from
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

## Top bar icon circle materials

- Status: intentional
- Area: component
- Shared intent: `OpenCodeTopBar` icon actions read as compact circular controls
  with the same size, placement, and accessibility labels on both platforms.
- iOS behavior: iOS 26+ uses interactive Liquid Glass circles for leading and
  trailing icon actions; below iOS 26 uses `OpenCodeButton` `iconCircle`.
- Android behavior: Material `OpenCodeButton` `IconCircle` (raised surface +
  border); no system Liquid Glass.
- Reason: Android has no Liquid Glass equivalent; product keeps shared layout
  while materials stay platform-native.
- Review trigger: if Android gains a system glass control API or product asks
  for a shared custom blur stack on top-bar icons.

## Top bar native Back input

- Status: intentional
- Area: component | motion | accessibility
- Shared intent: a page using the `OpenCodeTopBar` `back` variant can return
  exactly one visible level through both its visible Back button and the
  platform-native Back input. Overlay pages that use `back` chrome without a
  navigation push consume that native Back input so they dismiss the overlay
  rather than the screen underneath. Canceling an interactive input keeps the
  current screen active, and the visible button remains the accessible,
  discoverable alternative.
- iOS behavior: the `back` variant enables the navigation controller's standard
  locale-aware leading-edge interactive pop gesture while preserving the custom
  top bar and native transition progress, cancellation, and completion. Overlay
  pages set `consumesInteractivePop` so the gesture invokes the Back action
  instead of popping the destination underneath; a later pushed screen (Changes,
  child session) still pops normally because its top view controller has no
  overlay consumer.
- Android behavior: the Navigation 3 host continues to provide system and
  predictive Back; overlay pages install `BackHandler`. The top bar does not
  install a separate gesture.
- Reason: iOS and Android use different native Back conventions. Matching the
  shared navigation result is more platform-appropriate than imposing one
  gesture implementation on both apps.
- Review trigger: revisit if either app replaces its native navigation host or
  the product adopts custom cross-platform navigation motion.

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
  pinned to the bottom; scroll to the latest messages on tap. Keep Follow
  Latest armed only while the viewport is pinned near the bottom.
- iOS behavior: Session Chat uses a UIKit `UICollectionView` timeline host with
  SwiftUI-hosted cells. `TimelineScrollController` scrolls to the absolute
  bottom via `setContentOffset` and measures pin state from
  distance-from-bottom.
- Android behavior: Session Chat uses lazy Compose list state and scrolls to the
  bottom anchor item.
- Reason: SwiftUI `LazyVStack` / `scrollPosition` programmatic scrolling is
  unreliable for variable-height markdown chat rows and produced overlapping
  auto-follow snaps during streaming. UIKit virtualization keeps long-history
  performance and stable end anchoring while preserving Android follow
  semantics.
- Review trigger: revisit if hosted SwiftUI cells hitch on Big Session and need
  further cell or block virtualization.

## Overflow menu presentation

- Status: intentional
- Area: component | accessibility
- Shared intent: `OpenCodeTopBarOverflowHost` exposes the same entry model, enabled
  state, opaque elevated panel chrome, and dismissal behavior for compact
  top-bar overflow actions with a reserved leading icon slot and single-line
  label. The slot may contain a catalog icon or remain empty for aligned rows.
- iOS behavior: presented with a window-level overlay anchored below the
  measured top bar. `OpenCodeOverflowMenuTrigger` provides the standard `moreVert`
  button for top-bar placement.
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
- iOS behavior: the window overlay locks `form` mode to the 50% detent.
  Keyboard overlap shrinks available height and lifts the card so only the
  internal scroll region follows the keyboard.
- Android behavior: the full-window popup remains over an `adjustNothing`
  activity; `imePadding()` is applied to the form sheet boundary so it follows
  animated IME insets without resizing the activity content.
- Reason: native presentation and inset delivery differ, but both implementations
  preserve the same visible ownership boundary and interaction behavior.
- Review trigger: revisit if both platforms adopt a shared native modal API with
  equivalent detent and keyboard-inset semantics.

## Dialog presentation

- Status: intentional
- Area: component | motion
- Shared intent: `OpenCodeDialog` shows a centered elevated card with title,
  optional subtitle, optional single text field, and exactly two options over a
  dimmed scrim. Scrim tap and system dismiss request the secondary path.
- iOS behavior: presented as a window-level overlay with fade in/out; keyboard
  safe area lifts the card when the text field is focused.
- Android behavior: presented with Compose `Dialog` and a custom card;
  `imePadding()` keeps the card above the IME.
- Reason: each platform uses its native modal host while sharing OpenCode
  surface, radius, typography, button, and text-input composition.
- Review trigger: revisit if product design requires identical enter/exit motion
  or a shared modal host abstraction.

## Floating chrome materials (Liquid Glass vs raised Surface)

- Status: intentional
- Area: component
- Shared intent: Floating Dock, Session Chat Composer shell, composer suggestion
  overlays, and Session Chat floating pills (Action / Review Changes / Session
  Todos) share one floating chrome family — rounded/dock chrome via
  `OpenCodeFloatingChrome` / `openCodeFloatingChrome`, capsules via
  `OpenCodeFloatingPillChrome` — with equivalent layout affordances per surface.
  Session List home also uses dense rows and bottom-fade geometry
  (`background.base` `0 → 0.7 → 0.7`, top = dock top + 16, solid to screen
  edge). Composer mode / model Choice Badges (including **Plan**) use the
  related capsule glass Choice Badge variant.
- iOS behavior: iOS 26+ `glassEffect` with no opaque underlay on floating
  chrome; glass on filter chips, the project scope control, dock CTAs, and
  key home controls. Pre–26 dock / composer inner controls use a transparent
  `text-strong` **0.05** wash with no stroke, matching the shadow-only
  floating chrome shells. Below iOS 26, floating chrome
  uses raised fill (0.95) plus light shadow; capsule
  glass Choice Badges / Filter Chips use fill-only `text.strong` stands-ins
  (0.14 selected / 0.08 unselected). Capsule chips, Choice Badge glass, and
  floating pills use **non-interactive** `glassEffect` plus subtle
  scale/opacity press — not `.interactive()` lens feedback. Multi-pill float
  stacks use `GlassEffectContainer`. Composer IconCircles stay non-glass in
  the OPE-99 shell-only slice.
- Android behavior: Material 3 raised `Surface` (fill alpha 0.95, light
  elevation) for floating chrome; dock CTAs and the project scope control use
  a transparent **0.05** wash as the glass stand-in; glass Choice Badge /
  Filter Chip stand-ins stay fill-only; no system Liquid Glass API.
- Reason: Android has no Liquid Glass equivalent; layout and product actions stay
  aligned while materials remain platform-native.
- Review trigger: if Android gains a system glass material API or product asks
  for a shared custom blur stack, revisit the Android mapping.

## Launcher icon depth (Liquid Glass vs adaptive parallax)

- Status: intentional
- Area: other
- Shared intent: On-device launcher is a flat two-layer “d” (dark body, light
  glass cap) on `#070707`. Depth comes from the OS, not baked lighting.
- iOS behavior: `AppIcon.icon` (Icon Composer). Body and glass cap are separate
  layers with Liquid Glass, specular, and translucency; the system renders
  Default / Dark / Clear / Tinted.
- Android behavior: Adaptive icon with color background, two-tone vector
  foreground, and a white monochrome silhouette for themed icons. Launchers may
  parallax foreground against background. No system glass material.
- Reason: iOS 26 supplies live glass lighting; Android does not. The same SVG
  paths feed both. A pre-rendered 3D PNG would fight iOS glass and collapse
  under Android theming.
- Review trigger: revisit if Android adds a system glass icon material.
  Store listings use a flattened raster of the same “d”; they cannot carry
  iOS glass or Android parallax.

## Bottom sheet display corner radius

- Status: intentional
- Area: component
- Shared intent: The sheet card is inset **8** logical px from the left, right,
  and bottom of the display. Bottom corners use display corner radius minus
  that inset so they share a center with the device curve. Top corners use
  the smaller `bottomSheet.cornerRadiusTop` (**24**).
- iOS behavior: Reads the screen display corner radius from the window scene
  (`_displayCornerRadius`; no public numeric API). Bottom corners use that
  minus inset. Top corners are 24. Missing or zero values use the 55 − 8
  fallback.
- Android behavior: API 31+ reads
  `WindowMetrics.windowInsets.getRoundedCorner()` (bottom leading/trailing;
  radius is px) for the bottom corners. Top corners are 24. Below API 31, or
  when the corner is missing, the bottom uses the same 55 − 8 fallback.
- Reason: Both platforms now resolve a live display radius and apply the
  same top/bottom split. iOS still has no public numeric API.
- Review trigger: if iOS adds a public display-corner API, read that and
  drop the KVC key.

## Launch screen branding

- Status: intentional
- Area: other
- Shared intent: Cold start follows system light/dark using app chrome
  (`background-base`), not a stuck light frame.
- iOS behavior: Generated `UILaunchScreen` (no branded glyph). System
  background already tracks appearance.
- Android behavior: `Theme.SplashScreen` + `ic_splash` with day/night
  `splash_colors.xml`. Light mode darkens the glass cap; dark mode keeps the
  source two-tone. Home-screen launcher stays the dark `#070707` tile.
- Reason: Android 12+ splash is a themed window plus a foreground icon; iOS
  generated launch screens do not take a custom glyph without a storyboard.
- Review trigger: if iOS adds a branded Launch Screen storyboard, or Android
  splash should match the launcher tile instead of `background-base`.
