# Permission Approval — Decisions

## Expose mobile decisions as product terms

The public mobile domain uses `allowOnce` and `deny`. Transport adapters map
those to `once` and `reject`. Ambiguous `approve`/`deny` wire values are removed.

## Treat the pending list as authoritative after reconnect

Foreground events update state incrementally, but `GET /permission` replaces
the pending set after initial connection and reconnect. Mobile never infers a
response succeeded merely because the connection dropped.

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
