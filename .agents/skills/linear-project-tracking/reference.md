# Linear Conventions — opencode-mobile

Read this reference when the sync loop must create or classify an issue. Query
Linear for current issue state; this file contains durable conventions, not a
live inventory.

## Workspace

| Item | Value |
| --- | --- |
| Team | `Opencode-mobile` (key `OPE`) |
| Project | `opencode apps` |
| Project URL | <https://linear.app/opencode-mobile/project/opencode-apps-f7d80fb00b5a> |

Query Linear for current milestones instead of maintaining an inventory here.
If none fits, leave the milestone unset rather than inventing a category.

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
- [ ] Incomplete actionable item
```

Keep one **Remaining** entry per incomplete item. A linked child issue may
replace the entry when the item needs independent scheduling; do not keep both
as separate work.

## State Mapping

| Repo signal | Linear state |
| --- | --- |
| Recognized scope without an accepted plan | Backlog |
| Planned scope with no implementation started | Todo |
| Any implementation item active or incomplete after work began | In Progress |
| Intended scope complete and no actionable item remains | Done |
| Explicitly superseded or abandoned scope | Canceled |

Blocked work remains **In Progress** unless the workspace has a distinct blocked
state or convention; record the blocker and dependency relationship.

## Priority and Relationships

- High: active V1 blockers.
- Medium: non-blocking V1 work and polish.
- Low: optional follow-ups.
- Use blocked-by or related relationships when the dependency is real and
  visible in Linear.
- Split platform issues only when independent scheduling is intentional.

## Connector Capabilities

Select the available Linear operations by capability:

| Need | Capability |
| --- | --- |
| Find current work | List or search issues filtered to `opencode apps` |
| Create or mutate work | Create/update issue, including state and relations |
| Read project context | Get project and milestones |
| Broadcast milestone change | Create a project status update |
| Add a durable category | Create an issue label |

Preserve real markdown line breaks in descriptions and updates.
