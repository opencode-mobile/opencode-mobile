# Permission Approval — Decisions

## Expose mobile decisions as product terms

The public mobile domain uses `allowOnce` and `deny`. Transport adapters map
those to `once` and `reject`. Ambiguous `approve`/`deny` wire values are removed.

## Treat the pending list as authoritative after reconnect

Foreground events update state incrementally, but `GET /permission` replaces
the pending set after initial connection and reconnect. Mobile never infers a
response succeeded merely because the connection dropped.

The same rule applies when a response returns `404`: immediately reload the
pending list. If the request is absent, remove the stale prompt without waiting
for another event or reconnect. If reconciliation fails or the request remains,
show that the permission is no longer available.

## Project permission snapshots through fresh session topology

Every permission snapshot is projected through a freshly loaded session tree,
with the active session first followed by its descendants in parent-first
order. This makes root-session requests deterministic and allows permissions
from children created after Session Chat opened to appear without rebinding the
screen. A transient session-list failure retains the last successful topology.

Permission reads and responses are scoped to the active worktree on both
platforms so identical session or permission identifiers in another project
cannot affect the open Session Chat.

## Keep persistent approval out of v1

The server and web support `always`; mobile intentionally does not. This keeps
the existing v1 security boundary and avoids remote configuration mutation.

## Make permission approval a composer state

Permission approval replaces the composer’s inner prompt-entry content while a
request is pending. It is not a separate component placed above or instead of a
copied composer shell. This gives both states the same container implementation,
width, shape, insets, surface, and border by construction. Draft prompt state is
preserved in the Screen Model and returns after the request resolves.

The shared container keeps state-specific feedback: neutral outline for prompt
entry, warning for a pending permission, and error after a failed response. Its
height animates when content changes, while permission copy and actions align to
the same inner insets and leading/trailing anchors as composer input and actions.

## Keep the design-system API semantic and feature-independent

`OpenCodeSessionComposer` is a Component Collection component on Android and
iOS. Its public input is a semantic prompt-or-permission state and its output is
a single semantic action callback. It does not accept repositories, transport
DTOs, session identifiers, or Session Chat Screen Models.

Session Chat owns the mapping between feature state/actions and the component
contract. This keeps the exact production composer available to other screens,
previews, and an interactive Component Gallery page without duplicating the
shell or importing feature-domain dependencies into the design system.
