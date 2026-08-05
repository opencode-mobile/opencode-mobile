# Prototype verdict — Home activity grouping

- **Date:** 2026-08-05
- **Ticket:** [OPE-107](https://linear.app/opencode-mobile/issue/OPE-107/home-activity-grouping)
- **Question:** How should the home session list highlight recent activity /
  sessions that need attention, instead of a flat last-changed list?
- **Follow-up:** Where should user-pinned older sessions sit in that hierarchy?
- **Run:** `npm run prototype:home-activity` → http://localhost:5181/?variant=A

## Verdict

**Winner: A — Time ladder**

- Eye-catching **Recent** stack at the top (recent activity + running/ready).
- Then **day sections** for the last week.
- Then **week sections** for everything older.
- Keep the existing project filter dock.

**No pin for now.** Pinned older conversations were explored (shelf, merge into
Recent, chip rail, Live/Fresh/Pin split) and deferred — they add hierarchy and
affordance cost before the ladder itself is validated in product.

### Rejected for now

| Variant | Why not |
| --- | --- |
| B Spotlight rail | Extra horizontal surface; weaker than a single Recent stack |
| C Attention inbox | Status-first fights the time mental model |
| B′ Pinned shelf | Useful later; skip until ladder ships |
| C′ Pins in Recent | Mixes “happening now” with “I stuck this here” |
| D′ Pin chip rail | Another chrome band competing with filters |
| E′ Live · Fresh · Pin | Over-splits attention before we need it |

## Fold into product when implementing

- Group home sessions as Recent → last-7-days by day → older by week.
- Recent should be visually distinct from day/week section headers.
- Do not ship pin in the first cut.
- Project filters remain orthogonal to time grouping.
- Recent membership and Settings window hours: see
  [`../decisions.md`](../decisions.md) (Running ∪ Ready ∪ updated within
  configured hours; default 4h).

Prototype UI stays throwaway under
`docs/features/home-activity-grouping/prototype/`. Capture on a `prototype/…`
branch if kept as primary source; do not merge prototype chrome into `main`.
