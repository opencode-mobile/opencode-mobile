# Linear Reference — opencode-mobile

## Labels

| Label | Use |
| --- | --- |
| Feature | User-facing feature slice |
| Improvement | Follow-up, refactor, extra coverage |
| Bug | Defect |
| iOS | Primary iOS work |
| Android | Primary Android work |
| Shared | KMP / cross-platform code |
| Manual QA | Human validation against local server |

## Initial Issue Inventory

Issues created during project init (2026-06-27). Use `list_issues` for current
state — IDs may grow.

### Done (V1 Foundations)

| Issue | Feature slug |
| --- | --- |
| OPE-5 | `server-connection` |
| OPE-6 | `project-list-home` |
| OPE-7 | `project-selection` |
| OPE-8 | `session-repository` |
| OPE-9 | `message-repository` |
| OPE-10 | `foreground-event-stream` |

### Done (Session Chat & Control)

| Issue | Feature slug |
| --- | --- |
| OPE-11 | `session-chat` |
| OPE-12 | `model-selection` |
| OPE-13 | `clear-data` |
| OPE-14 | `session-context` |

### Done (Rendered Markdown)

| Issue | Feature slug |
| --- | --- |
| OPE-15 | `ios-oniguruma-backend` |

### In Progress

| Issue | Feature slug |
| --- | --- |
| OPE-16 | `native-rendered-markdown` |
| OPE-17 | `theme` |
| OPE-26 | `linear-project-tracking` (agent skill) |

### Todo

| Issue | Feature slug |
| --- | --- |
| OPE-18 | `sync-probe` |
| OPE-19 | permission request UI (no docs slug yet) |
| OPE-20 | manual QA (cross-cutting) |
| OPE-21 | `session-context` verification |
| OPE-22 | `ios-oniguruma-backend` follow-ups |

### Backlog (V1 scope, not started)

| Issue | Feature slug |
| --- | --- |
| OPE-23 | session CRUD & fork |
| OPE-24 | abort active work |
| OPE-25 | session diff viewer |

## Feature slug → doc path

```text
docs/features/<slug>/
├── plan.md
├── progress.md
├── parity-gaps.md
└── decisions.md
```

## Out of scope for Linear (unless user asks)

- CI workflow tweaks with no product impact
- Agent/docs-only edits unrelated to a feature slice
- Pure refactors with no progress.md change

When in doubt, add a short comment or update the parent feature issue.
