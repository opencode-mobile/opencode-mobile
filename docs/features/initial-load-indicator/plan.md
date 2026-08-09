# Initial Load Indicator — Plan

## User goal

Show a calm, neutral Mid-Well Status Stack while Home and Session Chat wait
for first content, instead of a tinted system spinner or a blank well.

## In scope

- Design-system `OpenCodeStatusStack` on iOS and Android (gallery + previews).
- AppRoot / Navigation bootstrap uses Status Stack with Home cold-start copy
  `Loading sessions` / `Checking server…` (avoids accent-tinted system spinner).
- Home `Probing` / `Loading` content well uses Status Stack with locked copy
  `Loading sessions` / `Checking server…`.
- Session Chat timeline `Loading` uses Status Stack with locked copy
  `Loading messages` / `Fetching timeline…`.
- Preserve iOS UITest id `sessionChatLoading`; add Android `sessionChatLoading`
  test tag; optional Home `homeLoading` and AppRoot `appRootLoading`.

## Out of scope

- Pull-to-refresh overlays, add-project navigator, composer busy, Changes,
  Session Context, Settings logs.
- Disk-first skip of Home Probing/Loading (OPE-27 paint path; coordinate only).
- Skeleton / hairline / wellhead / capsule / dock-whisper alternatives.

## Server APIs

Not applicable — presentation only over existing Home probe/list and Session
message load states.

## Expected platform logs

Not applicable — no new network calls.

## Domain objects

Not applicable — uses existing Home and Session Chat Screen Model loading
states.

## Screen Model

- Home: when state is `Probing` or `Loading`, content well shows Status Stack.
- Session Chat: when timeline is `Loading`, content well shows Status Stack.
- No new user actions.

## Native navigation entry points

- Home: existing root Home screen after connection profile resolves.
- Session Chat: existing session open / bind path.

## Error states

Not applicable — probe failure and timeline error keep existing error chrome.

## Permission request behavior

Not applicable.

## Design-system components

| Need | Classification |
| --- | --- |
| Mid-Well Status Stack | New Component Collection: `OpenCodeStatusStack` / `status-stack.md` |

## Prototype / research

- Prototype verdict (Mid-Well Status Stack) is recorded in `decisions.md`.
- Implementation research:
  `docs/features/initial-load-indicator/research-ope-27-implementation.md`
