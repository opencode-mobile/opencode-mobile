# Theme System — Parity Gaps

*No current implementation gaps between iOS and Android.*

Both platforms ship the same 37 bundled presets, Settings theme picker, JSON
import, and persistence for selected plus imported themes.

## Intentional deferrals (both platforms)

These are product follow-ups, not platform parity issues:

| Gap | Intent | Next action |
| --- | --- | --- |
| Theme applies on restart only | Avoid mid-session color/token churn until live switching is designed | Follow-up issue for hot reload of resolved tokens; QA note: Settings subtitle updates immediately, full iOS recolor may need relaunch |
| Picker shows names only | Ship preset list without swatch UI | Follow-up issue for preview rows |
| No theme editor / export | Mobile is a consumer of OC-2 JSON, not an author | Revisit only if desktop adds mobile export |
