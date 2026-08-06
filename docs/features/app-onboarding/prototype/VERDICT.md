# Prototype verdict — App onboarding

- **Date:** 2026-08-06
- **Question:** Does the OPE-98 first-install flow feel right (classic pager +
  nested setup + locked unofficial/WIP copy)?
- **Run:** `npm run prototype:app-onboarding` → http://localhost:5183/?variant=A

## Verdict

**Winner: A — Classic pager**

- Full-screen slides with illustration area, title, body/list, page dots.
- Skip on every page; Next advances; final primary CTA is **Connect to server**.
- Page 2 **Need the server first?** opens nested **Set up opencode serve**;
  single CTA **Open server docs** → https://opencode.ai/docs/server/; Back
  returns to page 2.
- Locked copy matches OPE-98 / [`../plan.md`](../plan.md) (native WIP —
  not “community-built”).

## Rejected for now

| Variant | Why not |
| --- | --- |
| B Story scroll | Prefer discrete pages over a long first scroll |
| C Sheet over home | Prefer a dedicated first-install surface, not a sheet over Connection onboarding |

## Folded into feature docs

Product rules live in [`../plan.md`](../plan.md) and
[`../decisions.md`](../decisions.md). Implement native iOS/Android from the
plan; keep Connection setup as the exit from Connect (or Skip → home). Do not
ship the HTML switcher.
