---
name: github-issue-tracking
description: "GitHub issues: use to read an issue or backlog, create or split tickets, refine scope, update state or links, load an implementation contract, or resolve an OPE id."
---

# GitHub issue tracking

The GitHub issue owns ticket scope, acceptance, Remaining, status, and
temporary platform gaps. Use `gh` against `opencode-mobile/opencode-mobile`.
Read the issue before answering or changing it, and read it again after a
mutation. Report a missing issue or failed `gh` call instead of guessing.
Change only what the user asked to change.

## Before implementing ticket-backed work

Read the issue first. Its outcome, acceptance criteria, and Remaining list,
together with current code and tests, are the contract. Where the issue and
the code disagree, or the request goes beyond the issue, raise it before
changing the implementation. Do not write a plan into the repository; put it on
the issue if the user wants it recorded.

Read [conventions](reference.md) when creating an issue or classifying labels,
state, priority, milestone, or relationships.

## Keys

| Item | Value |
| --- | --- |
| Issue key | GitHub issue number, written `#367` |
| Legacy key | `OPE-<number>:` title prefix on imported issues |

Resolve a legacy key with
`gh issue list --state all --search "OPE-<number> in:title"`. `OPE-5` and `#5`
are different tickets; a pull request at that number is not the ticket.

## Commands

| Need | Command |
| --- | --- |
| Read one issue | `gh issue view <number> --json number,title,state,stateReason,labels,milestone,assignees,body` |
| Find current work | `gh issue list --state open --limit 50` |
| Create or edit | `gh issue create`, `gh issue edit --add-label/--remove-label` |
| Close / reopen | `gh issue close <number> --reason completed` or `--reason "not planned"`; `gh issue reopen <number>` |
| Open milestones | `gh api repos/opencode-mobile/opencode-mobile/milestones?state=open` |

Send real markdown line breaks in bodies and comments.
