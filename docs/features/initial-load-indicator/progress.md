# Initial Load Indicator — Progress

## Shared planning

- [x] Prototype Mid-Well Status Stack locked
- [x] Research on OPE-27 branch call sites
- [x] Feature plan

## Design-system dependencies

- [x] `docs/design-system/components/status-stack.md`
- [x] iOS `OpenCodeStatusStack` + gallery
- [x] Android `OpenCodeStatusStack` + gallery

## iOS

- [x] Home Probing/Loading → Status Stack
- [x] Session Chat Loading → Status Stack (`sessionChatLoading`)
- [x] AppRoot Loading → Status Stack (`appRootLoading`)
- [x] Simulator build succeeded

## Android

- [x] Home Probing/Loading → Status Stack
- [x] Session Chat Loading → Status Stack (`sessionChatLoading` testTag)
- [x] Navigation AppRoot Loading → Status Stack (`appRootLoading`)
- [x] `:app:compileDebugKotlin` succeeded

## Blockers

- None.

## Remaining work

1. Manual QA: empty-cache cold Home + Session; cache-hit Session brief Mid-Well
   until first frame; pull-to-refresh without Mid-Well.
2. Coordinate with OPE-27 disk-first Home skip when that path lands so Mid-Well
   does not flash on cache-hit Home.
