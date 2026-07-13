# Session Chat Composer

## Intent

`SessionChatComposerView` is the persistent interaction surface at the bottom of
Session Chat. It owns one shell and changes its content when the session needs a
blocking user decision, ensuring prompt entry and permission approval have
identical width, shape, inset, background, and border behavior.

## Public API

- Variants: prompt entry and permission approval.
- Prompt-entry states: empty, populated, sending-disabled, stop available, and
  model/mode/variant selection.
- Permission states: pending, submitting/disabled, queued, and response error.
- Required permission content: category, one or more patterns, allow-once action,
  and deny action.
- Optional permission content: queue position/count and error message.

The feature supplies semantic content and callbacks. It does not choose shell
colors, radii, spacing, or other visual overrides.

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

Both platform composer files include permission-state previews with stable
shell-command examples for light and dark theme review. Prompt-entry behavior is
reviewed in Session Chat.

## Parity And Divergence

No intentional divergence is defined. Temporary differences belong in
`docs/features/permission-approval/parity-gaps.md`.
