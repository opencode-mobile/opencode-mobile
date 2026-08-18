# Feature Documentation

Only **live** contracts live here. Shipped one-offs are listed below; details
are in git and Linear.

```text
docs/features/<slug>/
├── plan.md           # current state: goal, now, remaining
├── decisions.md      # still-binding choices only
└── parity-gaps.md    # only if an open iOS/Android gap exists
```

Do not add `progress.md`, ticket plans, or research dumps.

## Live

| Slug | Why it stays |
| --- | --- |
| [session-chat](session-chat/plan.md) | Session screen contract |
| [message-store](message-store/plan.md) | REST/SSE merge + display pipeline |
| [foreground-event-stream](foreground-event-stream/plan.md) | App-wide SSE |
| [server-api-v2](server-api-v2/plan.md) | Dual-stack + TUI v2 projection |
| [native-rendered-markdown](native-rendered-markdown/plan.md) | Shared highlighter |
| [ci-pr-gating](ci-pr-gating/plan.md) | Checks / `ruleset.md` |
| [android-release-distribution](android-release-distribution/operator-runbook.md) | Play / Firebase runbook |
| [ios-release-distribution](ios-release-distribution/operator-runbook.md) | TestFlight / Firebase runbook |

## Shipped (no live docs dir)

Home and connection: server-connection, multi-server, api-version-indicator,
app-onboarding, project-selection, project-list-home, add-project,
remove-project, session-list-project-picker, skip-all-chip-one-project,
home-session-actions, home-activity-grouping, session-repository,
session-rename, session-archive-delete, session-ready, session-project-identity,
sync-probe, new-session-progress, initial-load-indicator.

Chat: message-repository, file-attachments, permission-approval,
question-request, session-todos, review-changes, session-compact,
session-context, model-selection, slash-and-at-commands, assistant-tool-parts,
collapse-consecutive-tool-calls, composer-drafts, composer-liquid-glass,
liquid-glass-home-list, session-cache, session-cache-sync-indicator,
clear-data, diagnostic-logs.

Other: theme, rendered-markdown (superseded), retire-project-session-selector,
ios-oniguruma-backend, ios-ui-organization, release-observability,
ui-snapshot-evidence.

## New work

Create a directory only if the contract will stay live. Otherwise put the
ticket in Linear.