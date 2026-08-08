# Prototype verdict — Session Compact UX

- **Date:** 2026-08-07
- **Question:** How should Session Chat show compaction while it runs and after
  it finishes, given thin Commands `[compact]` already exists?
- **Run:** `npm run prototype:session-compact` → http://localhost:5185/?variant=B
- **Ticket:** [OPE-116](https://linear.app/opencode-mobile/issue/OPE-116/session-compact-richer-status-chrome)

## Verdict

**Winner: B — Status chrome only**

- Running: **Compacting…** in busy/status chrome (not only generic Working).
- Done: snackbar with context delta (`72% → 34%`) when measurable.
- No new timeline row; no float pill.

### Why B over C

Both were acceptable. B wins because compaction is rare and one-shot: it should
reuse Working / Stop / snackbar / context ring rather than compete with the
todos float stack. Checkpoint summary text is model-oriented; a sheet oversells
it. Add a sheet later only if users ask to read the summary.

## Rejected for now

| Variant | Why not |
| --- | --- |
| A Timeline checkpoint | Extra timeline surface; compaction parts stay hidden elsewhere |
| C Float pill + sheet | Fine, but wrong density for a rare action; float stack already busy |

## Folded into feature docs

Product rules live in `docs/features/session-compact/plan.md` and
`decisions.md`. Implementation task plan: `ope-116-plan.md`.
