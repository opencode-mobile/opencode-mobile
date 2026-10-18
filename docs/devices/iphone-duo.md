# iPhone Duo

Device reference for building and prototyping OpenCode Mobile on Apple's
foldable iPhone. Values were measured in Xcode 27.1's iOS 27.1 iPhone Duo
simulator with a probe app built on the iOS 27.1 SDK, unless a row says
otherwise. Machine-readable copy: [`iphone-duo/geometry.json`](iphone-duo/geometry.json).

All coordinates are points in the window's own space (origin top-left),
`@3x`.

## Displays

| | Outer | Inner |
| --- | --- | --- |
| Pixels | 1398×2034 | 2007×2853 |
| Points | 466×678 | 669×951 native portrait, 951×669 when open in landscape |
| Corner radii | 8 / 59 / 59 / 8 (UL, UR, LR, LL): square on the hinge side | 55 all round |
| Simulator display | `primary` | `primary-1` (native rotation 270) |

Model `iPhone19,4`. `userInterfaceIdiom` is `.phone` in every pose, so never
gate layout on idiom.

## Poses

Device Hub names the poses Closed, Partially Open (Book), and Open, plus
Rotate Right, which turns the device a further 90° each time it is pressed.

| Pose | Display | Window | Size class (h × v) | Safe area t / r / b / l | Bars |
| --- | --- | --- | --- | --- | --- |
| Closed | outer | 466×678 | compact × regular | 0 / 84 / 34 / 0 | vertical, trailing |
| Closed, turned | outer | 678×466 | compact × compact | 0 / 84 / 34 / 0 | vertical, trailing |
| Open | inner | 951×669 | regular × regular | 0 / 84 / 34 / 0 | vertical, trailing |
| Book (hinge ≈127°) | inner | 951×669 | regular × regular | 0 / 84 / 34 / 0 | vertical, trailing |
| Open, turned | inner | 669×951 | regular × regular | 82 / 0 / 34 / 0 | horizontal |

- The top inset is 0 in every pose except Open, turned. Content starts
  directly under the rounded top corners.
- With the 84pt rail and the 34pt bottom inset, an app gets 382×644 closed,
  594×432 closed and turned, and 867×635 open.
- Closed, turned in the table is one Rotate Right from Closed: the hinge is at
  the top and the camera at the bottom right. Turning the other way mirrors
  it, so the rail becomes a leading inset.
- The home indicator sits inside the 34pt bottom inset.

## System UI

**Status column.** The status bar is a column on the trailing edge, not a top
bar. From top to bottom it holds the time, a battery ring (with a charging
bolt) around the Wi-Fi glyph, and four cellular dots.

| Pose | Status region (occlusion) | Glyph column centre |
| --- | --- | --- |
| Closed | x 382–466, y 0–170 | x ≈417.5, time at y ≈92, below the camera |
| Open / Book | x 867–951, y 0–120 | x ≈902.5, time at y ≈40 |
| Closed, turned | none drawn | — |
| Open, turned | 82pt band across the top | time and ring in a row at the right, y ≈48 |

The glyphs sit 6.5pt leading of the rail's midline, not centred in it.

**Cameras.**
- Outer: a hole 37×37 at x 399, y 30 when closed, at the top of the status
  column. In Closed, turned it is at x 611, y 399, at the bottom of the rail.
  It is always present. `simctl io … screenshot --mask=black` draws it.
- Inner: an under-display camera. Its occlusion is 58×37 at x 677, y 21 (open),
  and it is reserved only while the camera is active. This value comes from a
  published probe, not from this measurement.

**Fold.** It is reported as a division region 40pt wide: x 455–495 when open or
in Book, y 455–495 when turned. `isActive` is false when flat and true in
Book.

## System bars (iOS 27.1 SDK)

- Bars are vertical in every pose except Open, turned, and sit on the
  trailing edge inside the 84pt rail, below the status column.
- Only the detail column of a split view participates. The sidebar keeps a
  horizontal bar, and the sidebar toggle stays at the leading edge of the top
  bar.
- Titles stay horizontal. Items with a symbol move to the rail; text-only items
  stay in the top bar.
- Rail order: Back or Close first, then the trailing items. Bottom-bar items go
  at the bottom of the rail.
- Rail items are 48pt glass circles on a 60pt pitch. First-item centres:
  - Open / Book: (902, 147)
  - Closed: (417.5, 198)
  - Closed, turned: (629.5, 48)
- The top bar sits at y 24–72 (48pt buttons) even though the top inset is 0.
  The leading button spans x 20–68, and an inline title starts at x ≈81.
- In Open, turned the bars go horizontal and sit inside the 82pt status band,
  beside the clock.
- iOS never converts custom bars (hand-built headers, `UIToolbar`,
  `UINavigationBar` or `UITabBar` contents). An app with custom chrome must
  draw the rail itself.

**SDK stamp decides the layout.** An app built with the iOS 26 SDK sits in
the middle of the opened inner display, with empty space around it. With the
iOS 27 SDK it fills the display but stays clear of the status column. With the
iOS 27.1 SDK it fills the display and gets vertical bars. Check the stamp with
`otool -l <binary> | grep -A4 LC_BUILD_VERSION`.

## APIs

- `GeometryProxy.reservedRegions(kind: .division | .occlusion, options: .includeInactive)`
  returns `[ReservedRegion]` with `frame` (in the proxy's space), `isActive`
  and `margins`. Regions arrive after the first layout pass, so read them on
  every evaluation. `UIView.reservedRegions(ofKind:options:)` is the UIKit
  equivalent.
- `@Environment(\.toolbarVerticalEdge)` (`HorizontalEdge?`) is `.trailing`
  where items can go vertical, and nil otherwise.
  `UITraitCollection.verticalBarEdge` is the UIKit trait.
- `.toolbarVerticalBehavior(.disabled)` opts a container out of the vertical
  bar. `ArrangementView` with `.split` / `.overlay` styles lays out two panes.
- All of these are `@available(iOS 27.1, *)`, and `#available` alone doesn't
  compile against older SDKs. Gate them with
  `#if canImport(SwiftUI, _version: 8.0.85)`: SwiftUI is 8.0.85.29 in the
  iOS 27.1 SDK.
- A `GeometryReader` that ignores the safe area reports no inset for that
  edge. To find the trailing inset, compare its width with that of a reader
  that respects the safe area.

## Simulator workflow

- **Install:** `xcodebuild -downloadPlatform iOS -buildVersion 27.1`
  (≈7.9 GB). The iOS 27.1 runtime only offers the iPhone Duo device type; its
  default device is named "iPhone Duo".
- **Change poses:** `simctl` has no pose command. Poses are buttons in
  `/Applications/Xcode.app/Contents/Applications/DeviceHub.app`; their
  accessibility help is "Closed", "Partially Open", "Open" and "Rotate Right".
  Rotate Right is relative, so check orientation from a screenshot before
  rotating again. With Accessibility allowed for the calling app, press one
  from the shell:

  ```javascript
  // osascript -l JavaScript press.js "Open"
  function run(argv) {
    const p = Application('System Events').processes['DeviceHub'];
    p.frontmost = true;
    p.menuBars[0].menuBarItems['Window'].menus[0].menuItems['iPhone Duo (iOS 27.1)'].click();
    delay(0.8);
    for (const c of p.windows[0].entireContents()) {
      let h = ''; try { h = c.help(); } catch (e) {}
      if (h === argv[0]) { c.click(); return 'pressed ' + h; }
    }
    return 'not found ' + argv[0];
  }
  ```
- **Screenshots:** capture each display with
  `xcrun simctl io <udid> screenshot --display=primary` (outer) or
  `--display=primary-1` (inner). Add `--mask=black` for rounded corners and
  the outer camera hole. Set a clean status bar first with
  `xcrun simctl status_bar <udid> override --time 9:41 --batteryState charged --batteryLevel 100`.
- **Measure:** a probe app that paints the safe area and prints size classes
  and insets settles any value in a few minutes. Build it with `swiftc` for
  `arm64-apple-ios27.1-simulator` and install the bundle with `simctl`.

## Prototype kit

- **Geometry:** take every number from
  [`iphone-duo/geometry.json`](iphone-duo/geometry.json): pose sizes, safe
  areas, size classes, status regions, camera holes, fold, frame margins and
  rail positions.
- **Device frames:** `scripts/iphone-duo-frames.sh <out-dir>` exports Apple's
  Duo frames from the local Xcode as SVG. The repository doesn't ship Apple's
  artwork.
  - Outer frame: 514×706, screen at (26, 14), with the hinge spine on the left.
  - Inner frame: a 660×924 composite with 17pt margins, scaled by 669/626 to
    fit the 669×951 screen. Rotate it 90° for Open.
  - Rotating the outer frame 90° clockwise gives Closed, turned. Its margins
    become top 26, right 14, bottom 22, left 14, and its corner radii
    become 8 / 8 / 59 / 59.
- **Status glyphs:** crop them from a simulator screenshot over the app's
  background colour. The regions are in the System UI table above.
