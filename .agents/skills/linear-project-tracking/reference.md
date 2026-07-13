# Linear Conventions — opencode-mobile

Read this reference when the sync loop must create or classify an issue. Query
Linear for current issue state; this file contains durable conventions, not a
live inventory.

## Workspace

| Item | Value |
| --- | --- |
| Team | `Opencode-mobile` (key `OPE`) |
| Project | `opencode apps` |
| Status document | [Implementation Status](https://linear.app/opencode-mobile/document/implementation-status-d505a23348e6) |
| Project URL | <https://linear.app/opencode-mobile/project/opencode-apps-f7d80fb00b5a> |

## Milestones

| Milestone | Feature slices |
| --- | --- |
| V1 Foundations | `server-connection`, `project-list-home`, `project-selection`, `session-repository`, `message-repository`, `foreground-event-stream` |
| Session Chat & Control | `session-chat`, `model-selection`, `session-context`, `clear-data`, permission UI, session CRUD/fork, abort |
| Rendered Markdown | `native-rendered-markdown`, `rendered-markdown` (superseded), `ios-oniguruma-backend`, session diff viewer |
| Polish & Validation | `theme`, `sync-probe` |

Use the closest existing milestone. If none fits, leave the milestone unset and
report the gap rather than inventing a durable category during a sync.

## Labels

| Label | Use |
| --- | --- |
| Feature | User-facing feature slice |
| Improvement | Follow-up, refactor, or extra coverage |
| Bug | Defect |
| iOS | Primary iOS work |
| Android | Primary Android work |
| Shared | Shared or cross-platform implementation |

Create a label only when no existing label expresses a durable category.

## Issue Shape

Use a product title such as `Session Chat`, not a filesystem slug.

```markdown
[One-line user outcome.]

**Remaining:**
- [ ] Incomplete actionable item from the canonical docs

Docs: `docs/features/<slug>/`
```

Keep one **Remaining** entry per canonical incomplete item. A linked child issue
may replace the entry when the item needs independent scheduling; do not keep
both as separate work.

## State Mapping

| Repo signal | Linear state |
| --- | --- |
| Recognized scope without an accepted plan | Backlog |
| Planned scope with no implementation started | Todo |
| Any implementation item active or incomplete after work began | In Progress |
| Intended scope complete and no canonical actionable item remains | Done |
| Explicitly superseded or abandoned scope | Canceled |

Blocked work remains **In Progress** unless the workspace has a distinct blocked
state or convention; record the blocker and dependency relationship.

## Priority and Relationships

- High: active V1 blockers.
- Medium: non-blocking V1 work and polish.
- Low: optional follow-ups.
- Use blocked-by or related relationships when the dependency is real and
  documented.
- Split platform issues only when independent scheduling is intentional and
  recorded in the feature docs.

## Connector Capabilities

Select the available Linear operations by capability:

| Need | Capability |
| --- | --- |
| Find current work | List or search issues filtered to `opencode apps` |
| Create or mutate work | Create/update issue, including state and relations |
| Read project context | Get project and milestones |
| Update status document | Read/update the existing Implementation Status document |
| Broadcast milestone change | Create a project status update |
| Add a durable category | Create an issue label |

Preserve real markdown line breaks in descriptions and updates.
