# Prototype verdict — App onboarding UI

- **Date:** 2026-08-02
- **Question:** What should multi-page onboarding look like on first install,
  before (or leading into) Connection Profile setup?
- **Run:** `npm run prototype:app-onboarding` → http://localhost:5181/?variant=A

## Verdict

**Winner: A — Classic pager**

- Full-screen slides with illustration area, title, body, page dots.
- Skip available; Next advances; final slide primary CTA is **Connect to server**.
- Separates education from the connection form.

### Content beats (locked for next fold-in)

1. **Unofficial client** — native client, WIP; not an official OpenCode product
2. **Connect to servers** — recommend Tailscale; optional CTA opens a separate
   **Set up opencode serve** screen (short steps retained; only CTA opens
   https://opencode.ai/docs/server/)
3. **Main features** — sessions, chat/abort, permissions, diffs, foreground
   live stream → primary **Connect to server**

## Rejected for now

| Variant | Why not |
| --- | --- |
| B Story scroll | Prefer discrete pages over a long first scroll |
| C Setup checklist | Too dry / setup-wizard; less of a first-open moment |
| D Capability flash | Stylish, but classic pager is the chosen pattern |

## Next

Folded into feature spec: [`../plan.md`](../plan.md),
[`../decisions.md`](../decisions.md). Implement native iOS/Android from the
plan; keep Connection setup as the exit from Connect (or Skip → home). Do not
ship the HTML prototype.
