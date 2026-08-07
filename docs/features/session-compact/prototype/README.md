# PROTOTYPE — Richer Session Compact UX

Throwaway webpage. Not for production.

**Question:** After Commands `[compact]` already works (toast + refresh), how
should Session Chat make compaction feel like a first-class operation — while
it runs, and after it finishes?

**Proposal (short):** keep one user action (Commands → compact). Do not add a
separate Summarize product (`/summarize` ≡ `/compact`). Upgrade feedback only:

| Moment | What users need |
| --- | --- |
| Running | Know the session is compacting (not a generic “Working”) |
| Done | Know context was reduced, and optionally skim the checkpoint summary |
| Fail | Clear failure (already mostly covered by snackbar) |

Three structurally different homes for that feedback:

| Key | Name | Idea |
| --- | --- | --- |
| A | Timeline checkpoint | Compaction is a visible timeline row (running + done card) |
| B | Status chrome only | No new timeline surface — working label + context ring + snackbar |
| C | Float pill + sheet | Todos-style pill above composer; sheet holds the summary |

## Run

```bash
npm run prototype:session-compact
```

http://localhost:5185/?variant=A

Use the floating bar or `←` / `→`. Within each phone, use **Idle / Compacting /
Done** to flip the state under review.

## Verdict

**B — Status chrome only** won. See [VERDICT.md](./VERDICT.md).

Implementation: [OPE-116](https://linear.app/opencode-mobile/issue/OPE-116/session-compact-richer-status-chrome),
docs in the parent feature directory.

## Not for production

Fold only the validated decision into native code. Keep this HTML as a primary
source on a throwaway branch if needed; do not ship the switcher.
