# Initial Load Indicator — Parity Gaps

## Android Session Loading test tag (closing)

- Expected: both platforms expose `sessionChatLoading` for automation.
- iOS: `accessibilityIdentifier("sessionChatLoading")` on the Status Stack
  ProgressView.
- Android: `testTag = "sessionChatLoading"` on Status Stack (was missing when
  Loading was a blank Box).
- Status: aligned in this change; leave open only until UI automation confirms.

## Home dock during Probing/Loading

- Expected: verdict prefers chrome visible; Session already keeps composer.
- Current: both platforms show TopBar during Home Probing/Loading but Session
  List dock only under Empty/Loaded.
- Status: intentional for this cut — TopBar-only Home load; record if product
  later requires dock ghost during load.

## iOS warm peek vs Android bind Loading

- Expected: same when-to-show rules for Mid-Well.
- iOS: `peekWarmDisplayMessages` can skip `.loading` entirely.
- Android: `bind` still starts at `Loading` until first frame.
- Status: pre-existing Session Chat parity; Mid-Well only shortens or styles the
  Android interim. Owner: Session Chat / OPE-27 warm reopen follow-up.
