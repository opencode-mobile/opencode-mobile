# Research: Mid-Well Status Stack on OPE-27 Session Cache

**Date:** 2026-08-06  
**Branch inspected:** `feature/ope-27-session-cache` @ `3ab188f` (via `git show`; current worktree not switched)  
**Linear:** [OPE-27 Session Cache](https://linear.app/opencode-mobile/issue/OPE-27/session-cache)  
**Locked visual:** [`decisions.md`](./decisions.md#mid-well-status-stack-is-the-loading-chrome)

## Executive summary

Replace the tinted system indeterminate spinner in the **Home content well**
(`Probing` / `Loading`) and the **Session Chat timeline well** (`.loading` /
`Loading`) with a shared **Mid-Well Status Stack**: centered ~32pt neutral ring
+ primary label + weak subline. Keep surrounding chrome (Home top bar; Session
top bar + composer). Do **not** touch AppRoot bootstrap, add-project navigator,
pull-to-refresh overlays, composer model spinners, Changes, Session Context, or
Settings log screens.

**When it shows (after OPE-27 as the branch actually behaves today):**

| Surface | Show Mid-Well | Skip / stay blank-content |
| --- | --- | --- |
| Home `Probing` / `Loading` | Yes — cold connect and every full `runHomeLoad` that is not `preserveContent` | Pull-to-refresh (`preserveContent`); already `Loaded`/`Empty` while soft-refreshing |
| Home with disk session list | **Still shows spinner today** until REST `listSessions` finishes; LiveStore disk seed only runs **after** `Loaded` | Linear claims skip when worktrees exist — **not implemented in UI path** (see Branch interaction) |
| Session Chat `Loading` | Yes — cold open / cache miss / bind before first frame | iOS warm in-memory `peekWarmDisplayMessages` → `.ready` immediately; cache seed then leaves `Loading` quickly; pull-to-refresh overlay is separate |
| AppRoot `Loading` | No | Profile bootstrap before Home chrome exists |

**What to build:** one reusable design-system status-stack component (both
platforms + gallery), then swap four Home/Session call sites. Coordinate with
any remaining OPE-27 disk-first home paint so Mid-Well does not flash on cache
hits that should skip the well entirely.

## Branch / OPE-27 interaction

### What Session Cache already does

- Deep `SessionCache` + Room/SwiftData; LiveStore / MessageSync emit
  disk-first frames with `isFromCache`
  (`docs/features/session-cache/plan.md`,
  `docs/adr/0006-session-cache-stale-while-revalidate.md`,
  `DefaultSessionLiveStore` seed + `MessageSync` `cacheHandle.seed()`).
- Freshness plumbed to UI as `sessionsFromCache` / `messagesFromCache`
  (commit `7a130fd`).
- Composer / mutations gated when `connectionStatus != Live`; stale chrome is
  existing connection status only — no cache banner
  (`docs/features/session-cache/decisions.md`, commit `3ab188f`).
- Remaining on branch docs: manual offline cold-start verification, then PR
  (`docs/features/session-cache/progress.md`).  
  Note: `parity-gaps.md` still says composer offline gate is unfinished; tip
  commit `3ab188f` landed that work — treat `progress.md` + tip as current.

### How cache changes when the spinner appears

**Home cold start (code on tip):**

1. `runHomeLoad` sets `.probing` / `Probing`, probes the server, then sets
   `.loading` / `Loading` before reading added worktrees
   (`ProjectListHomeViewModel.swift` ~376–406;
   `MainScreenViewModel.kt` ~326–358).
2. Initial sections come from **network** `sessionRepository.listSessions`, not
   `sessionCache.seedSessions`
   (`loadSection` in both home ViewModels).
3. Only after `Loaded` does `startLiveObservations` subscribe to
   `observeSessions`, which **then** can emit a disk seed with `isFromCache`
   (`DefaultSessionLiveStore` seed-before-network).

So on this branch, **Mid-Well still appears on every cold home load that hits
`Probing`/`Loading`**, including when disk cache and worktrees already exist.
Linear OPE-27 marks “Disk-first home cold start (skip Probing/Loading spinner
when worktrees exist)” done; **repository UI path does not skip those states**.
Implementing Mid-Well alone will restyle that wait; skipping it for cache hits
is a separate (still open in code) OPE-27 home paint change.

**Session Chat open:**

1. Timeline starts at `.loading` / `Loading` (region state defaults;
   Android `bind` forces `Loading`).
2. MessageSync may emit cache-seed messages with `isFromCache = true` before
   REST; pipeline publishes structure/plain-first frames; UI leaves `Loading`
   for `.ready`/`.empty` once rows publish
   (`MessageSync` seed path; `SessionChatTimeline` apply).
3. iOS only: non-empty `peekWarmDisplayMessages` skips `.loading` and paints
   `.ready` immediately (`SessionChatTimeline.swift` `startObserving`).
4. Linear’s “blank Loading chrome (no spinner wait for Final)” describes
   **not blocking on Final markdown enrichment** — domain already paints
   structure/plain first. The **Loading well still uses a system spinner**
   until the first published frame
   (`SessionChatTimelineView` ProgressView /
   `CircularProgressIndicator`). Mid-Well replaces that interim chrome; it
   should not reintroduce a wait for Final.

**Pull-to-refresh:** `preserveContent` keeps Loaded/Empty; no full-well
spinner (`pullToRefresh` / `runHomeLoad(preserveContent: true)`). Soft
refresh overlays (`isRefreshing` ProgressView on Session ready timeline) are
out of Mid-Well scope.

## Call sites to change

### In scope — replace system spinner with Mid-Well Status Stack

| Platform | File | Evidence | Notes |
| --- | --- | --- | --- |
| iOS Home | `ios/OpenCode Mobile/OpenCode Mobile/UI/Main/ProjectListHomeView.swift` | `.probing, .loading` → `ProgressView()` ~245–247 | Top bar already outside `content` (~21–29). Dock / Session List **not** shown in these states — only spinner in well. Verdict wants dock chrome visible; may need layout so Session List dock (or equivalent) remains while well shows Mid-Well. |
| Android Home | `android/.../ui/main/MainScreen.kt` | `Probing`/`Loading` → `CircularProgressIndicator()` ~194–198 | Same: `OpenCodeTopBar` stays (~168+); list/dock only under `Empty`/`Loaded` via `HomeProjectsContent`. |
| iOS Session | `ios/.../UI/SessionChat/View/SessionChatTimelineView.swift` | `.loading` → `ProgressView()` + `accessibilityIdentifier("sessionChatLoading")` ~78–81 | Screen already keeps top bar + floating composer (`SessionChatView.swift`). |
| Android Session | `android/.../ui/sessionchat/view/SessionChatTimelineView.kt` | `Loading` → `CircularProgressIndicator()` ~106–110 | No `testTag` / semantics id today (parity gap vs iOS). Composer stays in `SessionChatScreen.kt`. |

### State drivers (usually unchanged; only chrome)

| Platform | File | Evidence |
| --- | --- | --- |
| iOS | `ProjectListHomeViewModel.swift` | Initial `.probing`; `runHomeLoad` sets `.probing` then `.loading` ~77, ~382–403 |
| Android | `MainScreenViewModel.kt` | Initial `Probing`; same sequence ~68, ~334–355 |
| iOS | `SessionChatTimeline.swift` / `SessionChatUiModels.swift` | Defaults / `startObserving` set `.loading` unless warm peek |
| Android | `SessionChatTimeline.kt` / `SessionChatUiModels.kt` | `bind` → `Loading`; enum `SessionChatTimelineState.Loading` |

### Out of scope call sites (do not Mid-Well)

| Platform | File | Why |
| --- | --- | --- |
| iOS | `UI/Root/AppRootView.swift` ~57–58 | Pre-Home profile bootstrap; no Home chrome |
| Android | `Navigation.kt` ~118–119 | Same AppRoot loading |
| iOS/Android | Add-project navigator ProgressView / CPI in home sheets | Directory browse, not sessions initial load |
| iOS | `SessionChatTimelineView.swift` ~152 (`isRefreshing` overlay) | Soft refresh, not initial |
| Composer / status bar | `OpenCodeSessionComposer*`, `OpenCodeSessionStatusBar*` | Model / busy affordances |
| Changes, Session Context, Diagnostic / TextMate logs | Various | Unrelated screens |

## Design-system recommendation

**New Component Collection entry** — not feature-local, not an extension of
`OpenCodeEmptyState`.

Reasons:

- Same visual contract on Home + Session with only copy differences
  ([`decisions.md`](./decisions.md#mid-well-status-stack-is-the-loading-chrome)).
- No progress/loading component exists under `docs/design-system/components/`
  (empty-state, session-status-bar, etc.; none for indeterminate status stack).
- `OpenCodeEmptyState` is instructional empty (title `text-strong` /
  `type.heading`, message `text-base`, optional icon) — wrong hierarchy and no
  ring (`docs/design-system/components/empty-state.md`,
  `OpenCodeEmptyState.swift` / `.kt`).
- Session Cache plan expected **no new** reusable components for cache UX
  (`docs/features/session-cache/plan.md` Design-System table); Mid-Well is a
  separate initial-load-indicator concern.

Follow `.agents/skills/design-system-component/SKILL.md`: document under
`docs/design-system/components/`, implement iOS + Android, gallery + previews,
then wire features.

### Contract sketch — `OpenCodeStatusStack` (name TBD)

**Intent:** Centered mid-well indeterminate status for screen regions that are
waiting on first content (not soft refresh, not empty instruction).

**Public API (variant-first):**

- Variant: single semantic layout `indeterminateStatus` (or named
  `midWellStatus`). Optional screen presets via content only — prefer
  caller-supplied strings over a Home/Session enum so Settings or future
  screens can reuse without API churn.
- Required content: `title: String`, `subtitle: String`.
- Optional: `accessibilityIdentifier` / semantics test tag (iOS + Android).
- **Do not** expose tint color, stroke width overrides, hex, or arbitrary size
  as public API. Size locked ~32pt in component tokens.

**States:**

- `active` (indeterminate animation) — only applicable state for v1.
- Not applicable: pressed, disabled, error, empty, selected, determinate
  progress.

**Tokens:**

| Role | Token |
| --- | --- |
| Ring stroke | `text-base` (`OpenCodeColors.textBase` / mapped Compose color) |
| Ring track | `border-weak-base` or `text-weaker` (VERDICT: border / text-weaker) |
| Title | `text-base` + `type.body` (or body role matching primary label) |
| Subtitle | `text-weaker` + `type.bodySecondary` or `type.caption` |
| Background | transparent; inherits well |

Token tables: `docs/design-system/tokens.md` (`text-base`, `text-weaker`,
`border-weak-base` / `border-weaker-base`).

**Behavior:**

- Vertically + horizontally centered in caller-provided space.
- Indeterminate ring; **no** Material/SwiftUI accent/`primary` tint
  (Android default `CircularProgressIndicator` uses theme `primary` =
  `text-interactive-base` in `Theme.kt` — that is the accent to avoid).
- Non-interactive; announce as progress/status for a11y.

**Review surfaces:** iOS + Android Component Gallery entries; light/dark
previews with Home and Session sample copy.

## Copy / a11y / identifiers

### Locked copy ([`decisions.md`](./decisions.md#mid-well-status-stack-is-the-loading-chrome))

| Screen | Primary (`text-base`) | Subline (`text-weaker`) |
| --- | --- | --- |
| Home (`Probing` and `Loading`) | `Loading sessions` | `Checking server…` |
| Session Chat (`Loading`) | `Loading messages` | `Fetching timeline…` |

Use the same Home pair for both `Probing` and `Loading` unless product later
splits probe vs list fetch.

### Accessibility

- Combine title + subtitle into a single accessible progress announcement (or
  label + value) so VoiceOver / TalkBack do not read three disconnected nodes.
- Keep ring decorative (`accessibilityHidden` / `contentDescription = null`)
  if text already conveys status; otherwise one progress region wrapping the
  stack.
- Preserve iOS UITest id **`sessionChatLoading`** on the Session stack (or a
  stable child). Today it is on `ProgressView` as
  `.accessibilityIdentifier("sessionChatLoading")`
  (`SessionChatTimelineView.swift` ~81;
  UITest `OpenCode_MobileUITests.swift` ~262).
- Add Android parity: `Modifier.semantics { testTag = "sessionChatLoading" }`
  (or equivalent) — **missing today**.
- Optional Home ids (not asserted yet): e.g. `homeLoading` for future UITests.

## Tests to update

| Test | Path | Change |
| --- | --- | --- |
| iOS UITest Big Session wait | `ios/.../OpenCode MobileUITests/OpenCode_MobileUITests.swift` ~262–265 | Still waits for `activityIndicators["sessionChatLoading"]`. After Mid-Well, either keep an accessibility progress/activity element with that id, or update query to the new semantics (static text / other). **Must not break** `waitForBigSessionHistory`. |
| Android Session Chat VM | `SessionChatViewModelTest.kt` ~818 | Asserts `timeline == Loading` on rebind — state enum stays; no chrome assert. Likely unchanged. |
| Home VM tests | `ProjectListHomeViewModelTests` / `MainScreenViewModelTest` | May still expect Probing/Loading transitions; update only if disk-first skip is implemented in the same change. |
| New | DS component unit/contract tests + gallery registration | Per design-system DoD. |
| New (optional) | Compose UI / snapshot for neutral ring (no primary tint) | Catch accent regression. |

No Android UITest currently asserts a Session loading tag (grep: none).

## Cross-platform parity

- Same Mid-Well component contract, copy, and when-to-show rules on both apps
  (synced Feature Slice under `docs/features/initial-load-indicator/` when
  implementing).
- Home layout gap: neither platform shows Session List dock during
  Probing/Loading; Session already keeps composer. Decide dock-during-load once
  for both.
- Warm reopen: iOS can skip Loading via `peekWarm`; Android `bind` always
  starts `Loading` (no `peekWarm` usage under `android/`). Cache seed still
  shortens Mid-Well on both; warm skip remains an intentional or temporary
  parity gap to record if left as-is.
- Identifier parity: add Android `sessionChatLoading`.

## Out of scope

- Skeleton rails, hairline, wellhead panel, capsule, dock whisper, accent
  system spinner (prototype rejected set, see
  [`decisions.md`](./decisions.md#mid-well-status-stack-is-the-loading-chrome)).
- Determinate progress, percentage, or multi-step wizards.
- Offline / stale banners (OPE-27 reuses connection chrome only).
- Implementing disk-first **skip** of Home Probing/Loading (OPE-27 product
  intent; not Mid-Well chrome) — coordinate only.
- AppRoot, navigator, Changes, Session Context, Settings logs, composer busy.
- Writing production UI in this research pass.

## Suggested implementation order

1. **Design-system contract** — add
   `docs/design-system/components/status-stack.md` (final slug/name), token
   mapping, gallery DoD; no feature wiring yet.
2. **Native component + gallery** — iOS `OpenCodeStatusStack`, Android
   Compose equivalent; neutral ring; light/dark previews.
3. **Session Chat swap** — replace Loading ProgressView/CPI; preserve
   `sessionChatLoading`; add Android testTag; fix UITest query if needed.
4. **Home swap** — replace Probing/Loading spinner; decide dock-during-load
   layout vs TopBar-only.
5. **Align with OPE-27 disk-first home** — if/when home seeds `Loaded` from
   cache before network, Mid-Well must not appear on that path; keep Mid-Well
   for true cold miss / probe / empty-cache.
6. **Docs** — `docs/features/initial-load-indicator/` plan/progress/decisions;
   note parity (warm peek, dock-during-load, test tags); link from Session
   Cache progress if coordination matters.
7. **Manual QA** — empty cache cold start (Mid-Well on Home + Session);
   cache-hit Session open (brief or skipped Mid-Well, structure-first paint);
   pull-to-refresh (no Mid-Well); offline after cache (list/chat content +
   connection chrome, not Mid-Well).

## Citations index

| Claim | Source |
| --- | --- |
| Winner Mid-Well Status Stack + copy + neutrals | `docs/features/initial-load-indicator/decisions.md` |
| OPE-27 scope / SWR / no new cache UI components | `docs/features/session-cache/plan.md` |
| Progress / remaining manual QA | `docs/features/session-cache/progress.md` |
| Offline chrome = connection only; freshness channel | `docs/features/session-cache/decisions.md` |
| ADR Session Cache | `docs/adr/0006-session-cache-stale-while-revalidate.md` |
| Linear checklist (disk-first / blank Loading claimed done) | Linear OPE-27 description |
| Home spinner UI | `ProjectListHomeView.swift` ~245–247; `MainScreen.kt` ~194–198 |
| Home state machine | `ProjectListHomeViewModel.swift` ~376–406; `MainScreenViewModel.kt` ~326–358 |
| Session spinner UI + id | `SessionChatTimelineView.swift` ~78–81; `SessionChatTimelineView.kt` ~106–110 |
| AppRoot spinner | `AppRootView.swift` ~57–58; `Navigation.kt` ~118–119 |
| LiveStore disk seed after subscribe | `DefaultSessionLiveStore` (Android/iOS) |
| MessageSync cache seed | `MessageSync` seed / `isFromCache` emit |
| iOS warm skip Loading | `SessionChatTimeline.swift` `startObserving` peekWarm |
| Empty state is not loading | `docs/design-system/components/empty-state.md` |
| Tokens text-base / text-weaker / border | `docs/design-system/tokens.md` |
| Android primary = interactive accent | `android/.../theme/Theme.kt` primary ← `text-interactive-base` |
| UITest sessionChatLoading | `OpenCode_MobileUITests.swift` ~262 |
| Component workflow | `.agents/skills/design-system-component/SKILL.md` |
