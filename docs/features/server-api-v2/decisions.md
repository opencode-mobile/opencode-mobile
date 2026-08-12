# Server API v2 — Decisions

## Stack phase PRs on an integration branch (2026-08-12)

**Choice:** Land all Server API v2 phases on
`feature/ope-146-server-api-v2`. Phase PRs (OPE-147…OPE-153) target that branch,
not `main`. Merge the integration branch to `main` once when the epic is ready.

**Why:** Keeps incomplete dual-stack work off `main` while still allowing
reviewable vertical slices. One ship decision for the whole migration.

**Alternative rejected:** Merge each phase to `main` as it finishes — would
expose half-routed remotes and capability gaps to production tracks before the
stack is coherent.

## Dual-stack behind repositories (2026-08-12)

**Choice:** Detect `v1` \| `v2` on connect; route under remotes/adapters;
keep Screen Models on existing domain types. Do not fork UI into protocol modes.

**Why:** Architecture already separates UI → repositories → remotes. Desktop
uses the same pattern (`detectServerProtocol` + compatible API). MessageSync/SSE
is the hard seam and belongs below UI.

**Alternative rejected:** Regenerate OpenAPI clients and feature-flag screens
per protocol — larger UI churn, worse parity.

## Capability flags for missing v2 twins (2026-08-12)

**Choice:** Expose `ServerCapabilities` (e.g. `supportsSessionMessageDiff`) and
hide/disable product actions on v2 when upstream has no equivalent.

**Why:** Review Changes “Last turn” is product-used but blocked on v2; better to
degrade than block all v2 support.

## Connection probe does not default to v2 (2026-08-12)

**Choice:** Mirror desktop classification for successful health payloads, but if
neither `/global/health` nor `/api/health` is usable, fail the connection probe
instead of defaulting to `v2`.

**Why:** Desktop `detectServerProtocol` defaults to `v2` when probes miss; that
is unsafe for Test Connection / home reachability (would mark dead servers as
reachable). Classification matrix still matches desktop for v1-only, v2-only
(`pid`), dual (prefer v1), and transitional `/api/health` without `pid`.

## V2 fork body uses `messageID` (2026-08-12)

**Choice:** P1 fork on v2 posts `{ messageID }` per the published OpenAPI
fixture and desktop dialog, not a nested `boundary` object.

**Why:** Research noted a possible `boundary: { type, messageID }` shape, but
the OpenAPI fixture and current desktop fork call use flat `messageID`. Prefer
the published contract until upstream documents otherwise.

## V2 chat history projects into domain Message (2026-08-12)

**Choice:** Map v2 `Session.Message.*` into today’s domain `Message` /
`MessagePart` in `MessageDomainAdapter` (not a Screen Model fork). Skip
`agent-switched` / `model-switched` / `system` as timeline rows (apply agent
and model onto later user/assistant messages). Expand `shell` into a user
command plus an assistant `bash` tool message. Keep `compaction` as its own
assistant message with `mode = compaction`.

**Why:** MessageSync, the display pipeline, and Session Chat already consume
domain `Message`. Desktop uses a similar projection into v1-shaped
messages/parts. Static history can ship without waiting for P3 event
normalization.

**Alternative rejected:** Keep v2 wire types in the store — would fork
MessageSync and timeline rendering per protocol.
