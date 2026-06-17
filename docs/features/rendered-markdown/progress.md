# Rendered Markdown Progress

## Shared Planning

- Status: implemented for Chat Markdown MVP.
- Shared scope: conditional Rendered Markdown for user and assistant message
  text, safe local renderer bundle, fenced code, and fenced diff blocks.
- Chat optimization: inline-only markdown stays native/plain; WebViews are
  reserved for block markdown, tables, code, and diff content.

## Design System

- Added `Rendered Markdown` component documentation.
- Added Component Gallery entries on iOS and Android.

## Android

- Added Compose WebView wrapper backed by bundled local HTML.
- Integrated Rendered Markdown into Session Chat assistant rows and
  `OpenCodeOutgoingMessage`.
- Rendered markdown height changes now feed the Session Chat Follow Latest
  scroll trigger.
- Added renderer HTML caching, WebView reuse hooks, stale callback guards, and
  per-message height caching for smoother timeline scroll.
- Fixed an Android-visible short-message sizing bug by measuring the rendered
  root content instead of the document viewport height.
- Added gallery preview.

## iOS

- Added SwiftUI WKWebView wrapper backed by bundled local HTML.
- Integrated Rendered Markdown into Session Chat assistant rows and
  `OpenCodeOutgoingMessage`.
- Rendered markdown height changes now feed the Session Chat Follow Latest
  scroll trigger.
- Added renderer HTML caching, shared WebKit process pool prewarm, stale
  callback guards, and per-message height caching for smoother timeline scroll.
- Added gallery preview.

## Validation Status

- Renderer JS smoke test passed on 2026-06-17:
  `npm run rendered-markdown:test`.
- Android debug Kotlin compile passed on 2026-06-17:
  `./gradlew :app:compileDebugKotlin`.
- Android focused detector unit test passed on 2026-06-17:
  `./gradlew :app:testDebugUnitTest --tests dev.opencode.mobile.ui.components.OpenCodeRenderedMarkdownDetectorTest`.
- iOS simulator debug build passed on 2026-06-17:
  `xcodebuild -project 'ios/OpenCode Mobile/OpenCode Mobile.xcodeproj' -scheme 'OpenCode Mobile' -destination 'generic/platform=iOS Simulator' -configuration Debug build`.
- iOS focused detector test passed on 2026-06-17:
  `xcodebuild test -project 'ios/OpenCode Mobile/OpenCode Mobile.xcodeproj' -scheme 'OpenCode Mobile' -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:'OpenCode MobileTests/OpenCodeRenderedMarkdownDetectorTests'`.
- Android ktlint remains blocked by pre-existing unrelated violations in
  `DefaultMessageLiveStore.kt`, `MessageDomainAdapter.kt`,
  `BottomSheetGalleryScreen.kt`, `SessionContextViewModel.kt`,
  `OpenCodeSessionStatusBar.kt`, and test sources.

## Remaining Work

- Dedicated session diff viewer.
- Per-code-block copy controls.
