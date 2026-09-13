# Linear conventions

Read this when creating an issue or classifying its labels, state, priority,
milestone, or relationships. Query Linear for current issue state. This file
holds durable conventions, not a live inventory.

## Workspace

| Item | Value |
| --- | --- |
| Team | `Opencode-mobile` (key `OPE`) |
| Project | `opencode apps` |
| Project URL | <https://linear.app/opencode-mobile/project/opencode-apps-f7d80fb00b5a> |

Query Linear for current milestones. If none fits, leave the milestone unset.

## Labels

| Label | Use |
| --- | --- |
| Feature | User-facing feature slice |
| Improvement | Follow-up, refactor, or extra coverage |
| Bug | Defect |
| iOS | Primary iOS work |
| Android | Primary Android work |
| Shared | Shared or cross-platform implementation |

Reuse an existing durable label. Create one only when no existing label names
the category.

## Issue shape

Use a product title such as `Session Chat`, not a filesystem slug.

```markdown
[One-line user outcome.]

**Remaining:**
- [ ] Incomplete actionable item
```

Keep one Remaining entry per incomplete item. When an item needs independent
scheduling, replace its Remaining entry with a linked child issue. Do not keep
the same work in both places.

## State mapping

| Repo signal | Linear state |
| --- | --- |
| Recognized scope without an accepted plan | Backlog |
| Planned scope with no implementation started | Todo |
| Any implementation item active or incomplete after work began | In Progress |
| Intended scope complete and no actionable item remains | Done |
| Explicitly superseded or abandoned scope | Canceled |

Blocked work stays In Progress unless the workspace has a distinct blocked
state. Record the blocker and the dependency.

## Priority and relationships

- High: active V1 blockers.
- Medium: non-blocking V1 work and polish.
- Low: optional follow-ups.
- Use blocked-by or related relationships when the dependency is real and
  visible in Linear.
- Split platform issues only when independent scheduling is intentional.

## Connector capabilities

Pick the available Linear operations by capability:

| Need | Capability |
| --- | --- |
| Find current work | List or search issues filtered to `opencode apps` |
| Create or mutate work | Create/update issue, including state and relations |
| Read project context | Get project and milestones |
| Broadcast milestone change | Create a project status update |
| Add a durable category | Create an issue label |

Send real markdown line breaks in descriptions and updates.
