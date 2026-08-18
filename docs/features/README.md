# Feature Documentation

One directory per feature. Keep it **current**, not a diary.

```text
docs/features/<feature-slug>/
├── plan.md           # current state: goal, now, remaining
├── decisions.md      # still-binding choices only
└── parity-gaps.md    # only if an open iOS/Android gap exists
```

Do not add `progress.md`, ticket plans, research dumps, web-specs, or soak
reports. Those go stale and block later work. History lives in git.

## `plan.md`

Short. Rewrite in place when the feature changes.

- user goal
- what shipped (now)
- remaining work, if any
- server APIs that still matter

Do not keep a changelog of every ticket.

## `decisions.md`

Only choices that still constrain implementation. When a choice is reversed,
replace it — do not pile superseded essays.

ADRs stay in `docs/adr/` for hard-to-reverse project trade-offs.

## `parity-gaps.md`

Omit the file if there are no open gaps. Each remaining gap: intent, iOS,
Android, reason, next action, temporary vs intentional.

## Optional ops files

Keep only if they are still run:

- `operator-runbook.md` — release/ops
- `ruleset.md` — CI contract

## New features

Create `plan.md`. Add `decisions.md` / `parity-gaps.md` only when needed.
Do not scaffold empty files.
