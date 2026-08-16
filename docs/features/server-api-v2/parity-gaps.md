# Server API v2 — Parity Gaps

No open platform parity gaps. Archive is capability-gated off on both apps.
Child listing uses `parentID` on both remotes. Session-scoped v2 fork and
message routes omit `?directory=` on both platforms (location lives on the
session).

## Intentional non-gaps

| Topic | Note |
| --- | --- |
| Last-turn session diff | Capability-gated off on v2 on both platforms |
| Todos REST seed | 404 on v2 so an empty list does not wipe event-seeded todos |
| Reasoning live/history | Left at v1 parity (not rendered) |
| Permission `always` | Mobile never sends persistent remember |
