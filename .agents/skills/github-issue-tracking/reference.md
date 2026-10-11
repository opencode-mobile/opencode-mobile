# GitHub issue conventions

Read this when creating an issue or classifying its labels, state, priority,
milestone, or relationships. Query GitHub for current issue state. This file
holds durable conventions, not a live inventory.

## Workspace

| Item | Value |
| --- | --- |
| Repository | `opencode-mobile/opencode-mobile` |
| Issue key | GitHub issue number, written `#367` |
| Legacy key | `OPE-<number>:` title prefix on imported issues |

Resolve a legacy key by searching `in:title`. `OPE-5` and `#5` are different
tickets. `gh issue view` on a number that is a pull request is not the ticket.

Query open milestones with `gh api`. If none fits, leave the milestone unset.

## Labels

| Label | Use |
| --- | --- |
| `enhancement` | User-facing feature slice |
| `improvement` | Follow-up, refactor, or extra coverage |
| `bug` | Defect |
| `ios` | Primary iOS work |
| `android` | Primary Android work |
| `shared` | Shared or cross-platform implementation |
| `backlog` | Recognized scope without an accepted plan |
| `todo` | Planned scope with no implementation started |
| `in-progress` | Implementation started, work still incomplete |
| `priority: urgent` | Needs attention before other open work |
| `priority: high` | Active V1 blocker |
| `priority: medium` | Non-blocking V1 work and polish |
| `priority: low` | Optional follow-up |

Reuse an existing durable label. Create one only when no existing label names
the category. An issue has one status label: `backlog`, `todo`, or
`in-progress`.

## Issue shape

Use a product title such as `Session Chat`, not a filesystem slug. Keep an
imported issue's `OPE-<number>:` prefix. New issues omit that prefix.

```markdown
[One-line user outcome.]

**Remaining:**
- [ ] Incomplete actionable item
```

Keep one Remaining entry per incomplete item. When an item needs independent
scheduling, replace its Remaining entry with a linked child issue. Keep that
work in one of those places.

## State mapping

| Repo signal | GitHub state |
| --- | --- |
| Recognized scope without an accepted plan | Open, label `backlog` |
| Planned scope with no implementation started | Open, label `todo` |
| Any implementation item active or incomplete after work began | Open, label `in-progress` |
| Intended scope complete and no actionable item remains | Closed, reason `completed` |
| Explicitly superseded or abandoned scope | Closed, reason `not planned` |

Moving into `in-progress` removes `backlog` and `todo`. Blocked work stays
`in-progress`. Record the blocker and the dependency on the issue.

Close and reopen with the reason:

```bash
gh issue close <number> --reason completed
gh issue close <number> --reason "not planned"
gh issue reopen <number>
```

Change status labels with `gh issue edit --add-label` and `--remove-label`.

## Priority and relationships

- `priority: high`: active V1 blockers.
- `priority: medium`: non-blocking V1 work and polish.
- `priority: low`: optional follow-ups.
- `priority: urgent`: work that preempts the current open set.
- Link a child with the sub-issue API. `sub_issue_id` is the child's numeric
  `id` from `gh issue view <number> --json id`, not the issue number.

```bash
gh api repos/opencode-mobile/opencode-mobile/issues/<parent>/sub_issues \
  -f sub_issue_id=<child_id>
```

- Record a related issue as a `#<number>` link in the body.
- In a pull request, name the issue as `#<number>`. Write `Fixes #<number>`
  only when that pull request finishes the issue.
- Split platform issues only when independent scheduling is intentional.

## Commands

| Need | Command |
| --- | --- |
| Read one issue | `gh issue view <number> --json number,title,state,stateReason,labels,milestone,assignees,body` |
| Find current work | `gh issue list --state open --limit 50` |
| Resolve a legacy id | `gh issue list --state all --search "OPE-<number> in:title"` |
| Create or edit | `gh issue create`, `gh issue edit` |
| Read milestones | `gh api repos/opencode-mobile/opencode-mobile/milestones?state=open` |

Send real markdown line breaks in descriptions and comments.
