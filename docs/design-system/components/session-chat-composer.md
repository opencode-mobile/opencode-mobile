# Session Chat Composer

## Intent

`OpenCodeSessionComposer` is the persistent interaction surface at the bottom of
Session Chat. It owns one shell and changes its content when the session needs a
blocking user decision, ensuring prompt entry, permission approval, and question
request have identical width, shape, inset, background, and border behavior.

## Public API

- Component state: `prompt`, `permission`, `question`, or `childLockout`.
- Prompt-entry states: empty, populated, pending attachments,
  pending revert, attach-disabled, sending-disabled, stop available, and
  model/mode/variant selection.
- Child-lockout states: default display of the model and thinking-effort
  used for that child session. Pressed applies only to the back action.
- Permission states: pending, submitting/disabled, queued, and response error.
- Question states: pending, multi-question wizard step, multi-select, custom
  answer, submitting/disabled, and response error.
- Required permission content: opaque request and session identifiers, category,
  one or more patterns, allow-once action, and deny action.
- Optional permission content: queue position/count and error message.
- Required question content: opaque request and session identifiers, current
  question index and count, header, question text, options (`label` plus
  `description`), selection mode (`multiple`), whether custom answer is allowed,
  selected option labels, custom text and whether custom is selected, and
  enabled flags for back, next, and submit.
- Optional question content: error message.
- Required child-lockout content: model label, thinking-effort (variant)
  label, whether to show the effort chip, lockout caption, and a back-to-main
  action.
- Optional child-lockout content: none. Mode is omitted. Chips are
  display-only.

The public native API accepts one semantic state value and one action callback.
Prompt actions are text changed (text plus selection), select model, select mode,
select variant, attach, open commands, remove attachment, cancel revert, send,
long-press send (V2 follow-up mode flip), and stop.
Prompt content includes `attachEnabled` so the feature can disable Attach when
the selected model lacks image and PDF input. Prompt text may include mention
ranges that render with the `syntax-type` token (inline `@name` agent
mentions). Pending attachment content uses presentation-safe
`OpenCodeAttachmentChip` state. Pending revert is a boolean on prompt state;
the composer renders a full-width chip above attachments using the attachment
chip recipe (undo icon, title **Reverting this message**, kind **REVERT**,
remove control). The feature owns when the chip is shown.
Child-lockout actions are back to main session. Chips do not open pickers.
Permission actions are allow once and deny, and return the opaque request and
session identifiers rendered with the decision. Question actions are toggle
option, toggle custom, custom text changed, back, next, submit, and dismiss;
each returns the opaque request and session identifiers rendered with the
decision. The feature supplies product content and handles actions; it does not
choose shell colors, radii, spacing, motion, or other visual overrides. Domain
models, server DTOs, repositories, and Screen Models are not component inputs.

## Token Usage

- Shell tokens: Floating Chrome materials (`surface.raised` / glass; see
  `floating-chrome.md`) — same shadow-only raised card as Floating Dock, with
  no default hairline. Permission uses `warning` or `error` for a status
  outline only. Also `text-strong` and `text-base`.
- Status/action tokens: `warning`, `error`, `button-primary-base`,
  `interactive`, and `text-on-brand-base` (send `IconCircle` accent contrast).
- Shared shell geometry: 26 logical-pixel continuous corner radius, 12
  logical-pixel content padding, 12 logical-pixel screen gutters, 4
  logical-pixel bottom inset (Floating Dock parity), and 10 logical-pixel row
  spacing. Session Chat places the composer (and its suggestion overlay) in
  the same `layout.contentMaxWidth` column as the timeline so the shell does
  not span a tablet.
- Prompt text, permission copy, and question copy use the same additional 4
  logical-pixel horizontal and vertical inset. Permission and question actions
  occupy the same leading and trailing positions as the attach and send actions.
- Child-lockout caption uses caption typography and `text-base`. Display-only
  model and thinking-effort chips stay fill-only capsules (`text-strong` at
  **0.14**, button typography) — not interactive Choice Badges. The back
  action uses the Session List Dock **New session** CTA: full-width capsule,
  height **52**, `text-strong` label, and the same glass / **0.05** wash
  recipe with no stroke. Do not use high-contrast `OpenCodeButton`
  `fullWidthText`.
- Typography: body for prompt entry; placeholder uses `text-weak` (not
  `text-base` / on-surface-variant). Heading, body-secondary, code, caption, and
  button roles for permission approval, question request, and child lockout.
  Glass picker chips use `type.button` and `text-strong` on both platforms.
  Question selection hints use `text-base`. Question options reuse
  `OpenCodeListItem` card style.

## Behavior

- Exactly one composer shell is mounted at the bottom of Session Chat.
- Without a pending request, a root session renders prompt entry and its
  normal controls. A child session (`parentID` present) renders child
  lockout instead: display-only model and thinking-effort chips, the caption
  “Child sessions cannot be prompted.”, and a primary “Back to main session”
  button. There is no text field, attach, commands, send, or stop.
- A pending permission or question request still replaces child lockout.
- Prompt send is available when trimmed text or at least one pending attachment
  exists. Pending revert, when shown, is a full-width removable chip above
  attachments and the prompt, stacked at 6–8 logical-pixel spacing, using the
  same surface, border, and remove control as `OpenCodeAttachmentChip`. The
  shell stays shadow-only. X reports `cancelRevert`. Pending
  attachments render as a vertical stack of full-width removable chips above
  the prompt input at 6–8 logical-pixel spacing. Attach is disabled
  when the selected model cannot accept image or PDF input; the feature owns
  that gate and passes `attachEnabled` into the composer.
- Send clears composer focus and dismisses the software keyboard (OPE-138;
  restores the OPE-62 chat send policy). Attach, Commands, and composer
  pickers do the same through the action-list bottom sheet. Focus stays
  view-local in the composer; Screen Models do not track keyboard visibility. Long-press send,
  when follow-up delivery is active, plays the Button context-action haptic
  and reports `toggleFollowUp`. Tap send stays silent.
- Model, mode, and thinking-effort (variant) pickers use `OpenCodeChoiceBadge`
  **glass** variant in the **unselected** (clear / clickable) chrome — they open
  pickers rather than toggling a selected peer in-row.
- The prompt action row is `[+] [/] … [send]`: Attach `IconCircle`, then a
  Commands `IconCircle` using the `slash` catalog icon at 8 logical-pixel spacing, a flexible
  gap, then stop (when active) and send. Attach, Commands, and Stop stay
  low-contrast. Send uses Button **accent** contrast (`interactive` fill,
  `text-on-brand-base` icon) so it follows the theme brand. An empty draft
  keeps send on muted accent; the first sendable character springs it to
  full accent. Commands opens a
  feature-owned Commands sheet; the composer only reports the tap as
  `openCommands`.
- With a pending permission or question request, the shell replaces its inner
  content with that decision UI. Draft prompt text and selection state remain in
  the Screen Model and return after the request resolves. After those requests
  resolve on a child session, the shell returns to child lockout, not prompt
  entry.
- Prompt, question, and child-lockout shells match Floating Dock: raised
  chrome and shadow, no hairline. A pending permission uses the warning
  outline; a failed permission response uses the error outline. Question
  response failures keep the shadow-only shell and surface the error in
  content copy.
- The shell coordinates content replacement with its size change when switching
  between prompt entry, permission approval, question request, and child
  lockout. Android fades
  outgoing content over 80 ms, resizes over 240 ms, and fades incoming content
  over 120 ms after an 80 ms delay. Loading/error updates that retain the same
  content key do not replay the full transition.
- The permission state has no dismiss action. Patterns wrap, remain selectable,
  and are treated as untrusted text.
- Allow once is primary for permission. During submission both decisions are
  disabled and the active mutation shows progress.
- The question state shows one question at a time with optional wizard progress
  (`N of M`), Back / Next when multiple questions exist, and Submit on the last
  question. Options use list-item cards with label, description, and
  `radio_button_checked` / `radio_button_unchecked` start decorators for
  selection. Multi-select questions may keep several labels selected. When
  custom answer is allowed, a dedicated custom row uses the same radio
  decorators; the custom text field is visible only while that radio is
  selected, and when unselected any entered custom value shows as the row
  subtitle. Submit is always explicit — including single-question
  single-select.
  Dismiss is an explicit control; the component does not dismiss on navigation
  or swipe.
- Feature code owns selection rules, wizard index, and enablement of Back /
  Next / Submit. During question submission, option and navigation actions are
  disabled and the active mutation shows progress.
- An error keeps the decisions available and announces the failure.
- The shell height remains content-adaptive in every variant, except prompt
  entry text: the prompt field grows with wrapped lines up to about **5 body
  lines**, then scrolls inside the field so chips and send stay above the
  keyboard (parity with iOS OPE-118 / Android OPE-127).

## Platform Notes

- iOS uses SwiftUI selection, accessibility, and progress primitives. Prompt
  text renders through a `UITextView`-backed representable (not SwiftUI
  `TextField`) so mention spans can be attributed with `syntax-type` while
  still reporting caret/selection changes back through `textChanged`; the
  representable applies caret placement only when `selectionRevision`
  changes, matching Android's revision-gated selection updates. Prompt height
  is measured from attributed text (`boundingRect`) and capped at
  `font.lineHeight * 5`. `UITextView.sizeThatFits` is not used: once internal
  scroll is on, that API returns the capped bounds and a follow-up update
  would turn scroll back off (OPE-213). Overflow scrolls inside the field.
  Question custom text uses `OpenCodeTextInput`. Shell fill uses shared
  `openCodeFloatingChrome` (iOS 26+ Liquid Glass + underlay; material fallback).
  The keyboard-avoidance host ignores the home-indicator safe area so the
  timeline paints behind the composer to the physical screen edge (parity with
  Session List). The composer overlay adds that inset only while the keyboard
  is hidden so the shell stays on the same float as Session List Dock.
- Android uses Compose selection, live-region semantics, and progress
  primitives. Prompt mention spans render through a `VisualTransformation` on
  `BasicTextField` (not `AnnotatedString` on `TextFieldValue`) so
  `syntax-type` styling survives IME updates that drop local span state; caret
  and selection sync from Screen Model values only when `selectionRevision`
  changes. Prompt `BasicTextField` uses `maxLines = 5` so growth stops and
  scrolls after about five body lines. Question custom text uses
  `OpenCodeTextInput`. Its fixed screen gutter and **4** logical-pixel bottom
  inset sit outside the animated content boundary. Session Chat applies one
  bottom inset on the composer overlay — `ime.union(navigationBars)` — so the
  shell lines up with Session List Dock (`nav` + **4**) and cannot stack a
  leftover IME inset on top of the nav bar. The timeline fade uses that
  measured overlay height and reaches the display edge. `AnimatedContent` coordinates the crossfade and
  `SizeTransform`; shared `OpenCodeFloatingChrome` (raised Surface) wraps that
  boundary so the shell redraws at each intermediate height instead of
  revealing already-switched content inside a lagging container. Permission
  status outlines, when present, travel with that chrome.
- Native progress indicators may differ while preserving shared state behavior.

## Review Surfaces

Both platform component files include permission-state, question-state,
child-lockout, and mention-styled prompt previews for light and dark theme
review. Both Component Galleries
expose one live, edge-to-edge composer with interactive controls for empty
prompt, populated prompt, mention-styled prompt, active work, pending
attachments, pending revert, child lockout, pending permission, queued
permission, permission submitting, permission error, single-question,
multi-question, multi-select, custom answer, question submitting, and
question error states, and log `openCommands`, `cancelRevert`, and
`backToMainSession` in the action log like the other prompt actions
(opening the Commands sheet itself is feature-owned, not shown in the gallery). The Mentions scenario seeds an
inline `@name` range with `syntax-type` color. The pending attachments scenario
shows at least two sample chips with empty draft text and send enabled. Question
scenarios keep local selection drafts so option, custom, Back / Next, Submit,
and Dismiss actions can be reviewed. The live composer is anchored to the bottom
of the available gallery page below its controls so screen-edge geometry and
resize motion can be reviewed in a realistic Session Chat placement.

## Completion Ledger

- Public API and applicable states: complete (`prompt`, `permission`,
  `question`, `childLockout`), including the `openCommands` prompt action
  (OPE-58) and `backToMainSession` (OPE-170).
- Token and typography mapping: complete. Shell materials use Floating Chrome
  (`floating-chrome.md`) with no default outline so the card matches Session
  List Dock; question selection hints use `text-base`. Permission still uses
  warning / error outlines. The Commands action uses `iconCircle` with the
  `slash` catalog icon; no new tokens for OPE-99 shell materials.
- iOS and Android implementations: complete for shared question-request composer
  state and for Floating Chrome shell materials (OPE-99). Control-level glass on
  IconCircles remains out of scope for this slice.
- Gallery entries and previews: complete for question / permission / prompt
  scenarios; materials are reviewable on the existing Session Composer gallery
  entry (edge-to-edge).
- Parity and divergences: intentional glass vs raised Surface via Floating
  Chrome; see `../divergences.md`.
- Automated checks: compile on iOS simulator and Android `compileDebugKotlin`
  for OPE-99 materials; Android `compileDebugKotlin` for OPE-127 prompt
  `maxLines`; iOS `OpenCodeMentionTextViewLayoutTests` for OPE-213 prompt
  height cap and scroll gating.

## Parity And Divergence

Intentional Floating Chrome material difference (iOS Liquid Glass vs Android
raised Surface) is documented in `../divergences.md`. Temporary feature
mismatches belong in `docs/design-system/divergences.md`.
