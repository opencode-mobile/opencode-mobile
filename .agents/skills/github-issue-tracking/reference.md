# GitHub issue conventions

Durable conventions, not a live inventory. Query GitHub for current state.

## Labels

| Label | Use |
| --- | --- |
| `enhancement` | User-facing feature slice |
| `improvement` | Follow-up, refactor, or extra coverage |
| `bug` | Defect |
| `ios` / `android` | Primary platform work |
| `shared` | Shared or cross-platform implementation |
| `backlog` | Recognized scope without an accepted plan |
| `todo` | Planned scope, no implementation started |
| `in-progress` | Implementation started, still incomplete (blocked work stays here; note the blocker on the issue) |
| `priority: urgent` | Preempts other open work |
| `priority: high` | Active V1 blocker |
| `priority: medium` | Non-blocking V1 work and polish |
| `priority: low` | Optional follow-up |

Reuse existing labels; create one only when no label names the category. An
issue has exactly one status label (`backlog`, `todo`, `in-progress`). Moving
to `in-progress` removes the other two. Close with reason `completed` when no
actionable item remains, `not planned` when superseded or abandoned. Leave the
milestone unset when no open one fits.

## Issue shape

Product title such as `Session Chat`, not a filesystem slug. Keep an imported
issue's `OPE-<number>:` prefix; new issues omit it.

```markdown
[One-line user outcome.]

**Remaining:**
- [ ] Incomplete actionable item
```

One Remaining entry per incomplete item. Work that needs independent
scheduling becomes a linked sub-issue instead of a Remaining entry, never both.
Split platform issues only when independent scheduling is intentional.

## Relationships

- Sub-issue: `sub_issue_id` is the child's numeric `id` from
  `gh issue view <number> --json id`, not the issue number.

```bash
gh api repos/opencode-mobile/opencode-mobile/issues/<parent>/sub_issues \
  -f sub_issue_id=<child_id>
```

- Related issue: a `#<number>` link in the body.
- In a pull request that finishes the issue, write `Fixes #<number>` so merging
  closes it; a bare `#<number>` only links it. Use the bare form while work on
  the issue stays open, and switch to `Fixes` once the pull request finishes it.
