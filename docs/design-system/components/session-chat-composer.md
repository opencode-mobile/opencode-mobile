# Session Chat Composer

## Intent

`OpenCodeSessionComposer` is the persistent interaction surface at the bottom of
Session Chat. It owns one shell and changes its content when the session needs a
blocking user decision, ensuring prompt entry and permission approval have
identical width, shape, inset, background, and border behavior.

## Public API

- Component state: `prompt` or `permission`.
- Prompt-entry states: empty, populated, pending attachments,
  attach-disabled, sending-disabled, stop available, and model/mode/variant
  selection.
- Permission states: pending, submitting/disabled, queued, and response error.
- Required permission content: opaque request and session identifiers, category,
  one or more patterns, allow-once action, and deny action.
- Optional permission content: queue position/count and error message.

The public native API accepts one semantic state value and one action callback.
Prompt actions are text changed, select model, select mode, select variant,
attach, remove attachment, send, and stop. Prompt content includes
`attachEnabled` so the feature can disable Attach when the selected model lacks
image and PDF input. Pending attachment content uses presentation-safe
`OpenCodeAttachmentChip` state. Permission actions are allow once and deny, and
return the opaque request and session identifiers rendered with the decision.
The feature supplies product content and handles actions; it does not choose
shell colors, radii, spacing, motion, or other visual overrides. Domain models,
server DTOs, repositories, and Screen Models are not component inputs.

## Token Usage

- Shell tokens: `surface-base`, `border-weaker-base`, `text-strong`, and
  `text-base`.
- Status/action tokens: `warning`, `error`, and `button-primary-base`.
- Shared shell geometry: 26 logical-pixel corner radius, 12 logical-pixel
  content padding, 12 logical-pixel screen gutters, 10 logical-pixel bottom
  inset, and 10 logical-pixel row spacing.
- Prompt text and permission copy use the same additional 4 logical-pixel
  horizontal and vertical inset. Permission actions occupy the same leading and
  trailing positions as the attach and send actions.
- Typography: body for prompt entry; heading, body-secondary, code, caption, and
  button roles for permission approval.

## Behavior

- Exactly one composer shell is mounted at the bottom of Session Chat.
- Without a pending request, it renders prompt entry and its normal controls.
- Prompt send is available when trimmed text or at least one pending attachment
  exists. Pending attachments render as a vertical stack of full-width removable
  chips above the prompt input at 6–8 logical-pixel spacing. Attach is disabled
  when the selected model cannot accept image or PDF input; the feature owns
  that gate and passes `attachEnabled` into the composer.
- With a pending request, the shell replaces its inner content with permission
  details and decisions. Draft text and selection state remain in the Screen
  Model and return after the request resolves.
- The shell keeps its normal neutral outline for prompt entry, uses the warning
  outline for a pending permission, and uses the error outline after a failed
  response.
- The shell coordinates content replacement with its size change when switching
  between prompt entry and permission approval. Android fades outgoing content
  over 80 ms, resizes over 240 ms, and fades incoming content over 120 ms after
  an 80 ms delay. Permission loading/error updates retain the same content key
  and do not replay the full transition.
- The permission state has no dismiss action. Patterns wrap, remain selectable,
  and are treated as untrusted text.
- Allow once is primary. During submission both decisions are disabled and the
  active mutation shows progress.
- An error keeps the decisions available and announces the failure.
- The shell height remains content-adaptive in both variants.

## Keyboard

Prompt entry follows the chat specialization in
[`keyboard-dismissal.md`](../keyboard-dismissal.md): the composer owns focus,
clears it on send / picker open / permission swap, and must not dismiss from
horizontal selector chip scrolling. An optional parent focus binding (iOS) or
`FocusRequester` (Android) exists only so Session Chat can refocus after
restore-to-composer. Timeline scroll dismiss is owned by Session Chat, not this
component.

## Platform Notes

- iOS uses SwiftUI selection, accessibility, and progress primitives.
- Android uses Compose selection, live-region semantics, and progress
  primitives. Its fixed screen gutter and bottom inset sit outside the animated
  content boundary. `AnimatedContent` coordinates the crossfade and
  `SizeTransform`; the background and outline wrap that boundary so the shell
  redraws at each intermediate height instead of revealing already-switched
  content inside a lagging container.
- Native progress indicators may differ while preserving shared state behavior.

## Previews

Both platform component files include permission-state previews with stable
shell-command examples for light and dark theme review. Both Component
Galleries expose one live, edge-to-edge composer with interactive controls for
empty prompt, populated prompt, active work, pending attachments, pending
permission, queued permission, submitting, and error states. The pending
attachments scenario shows at least two sample chips with empty draft text and
send enabled. The live composer is anchored to the bottom of the available
gallery page below its controls so screen-edge geometry and resize motion can
be reviewed in a realistic Session Chat placement.

## Parity And Divergence

No intentional divergence is defined. Temporary differences belong in
`docs/features/permission-approval/parity-gaps.md`.
