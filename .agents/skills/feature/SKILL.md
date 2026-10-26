---
name: feature
description: "Feature: use to resolve anchors, start ticket-backed work, handle requested docs, audit merge readiness, or publish."
---

# Feature

A feature is its GitHub issue, git branch, and pull request. Keep those three
pointing at each other. Do only what the user asked: resolving anchors never
changes state, and committing, pushing, or opening a pull request happens only
on an explicit request.

Issue mutations go through `github-issue-tracking`; implementation through
`synced-native-feature` and `design-system-component`. After another skill
mutates state, read the result back and verify the link.

## Resolve the issue and branch

Start from the strongest anchor the user gave: `#<number>`, legacy
`OPE-<number>`, the current branch, a pull request, or a feature name.

- A branch yields a key from the first `ope[-_/]<number>` match, else the first
  number in `feature/<number>-` or `fix/<number>-`. Fall back to `#<number>` or
  titles in recent commits.
- `OPE-<number>` is a title prefix on imported issues; resolve it with
  `gh issue list --state all --search "OPE-<number> in:title"`. `OPE-5` and
  `#5` are different tickets, and `gh issue view` on a pull request number is
  not the ticket.
- If evidence yields several candidates or none, ask before any ticket-linked
  mutation.

When starting work, prefer an existing local or remote issue branch. Otherwise
create one named per `AGENTS.md` **Issues, Branches, Documentation**. Leave
unrelated worktree changes untouched.

## Docs

Only when the user asked for documentation: change the requested document and
nothing else. Feature state stays on the issue, behavior in code and tests.

## Merge readiness

Check what applies and report pass, fail, or not applicable for each:

- branch name contains the issue key
- diff contains only the intended scope
- issue plan, status, and remaining work match the branch (when ticket state
  is in scope)
- relevant automated checks have a recorded pass, fail, or explicit skip
- the pull-request summary states scope, checks, and remaining risks
- evidence posted or explicitly not needed: user-visible or live-server
  changes have an evidence comment on the pull request per `AGENTS.md`
  **Pull Request Evidence**, or Checks says "No UI change" or why none applies

Repair issue state only when asked; otherwise report the mismatch.

## Publish

1. Review branch, status, and diff. Stage only the feature's files.
2. Commit with the issue number and concise scope, e.g.
   `#367 Add theme token previews`.
3. Push the current branch.
4. Open a draft pull request unless a ready one was requested. Body:

```markdown
## Summary
- ...

## Checks
- ...

## Evidence
<!-- Per AGENTS.md "Pull Request Evidence": "Screenshots in the evidence
     comment", or one line saying why none applies. -->

## Issue
- #<number>

## Risks / Follow-ups
- ...
```

5. **Evidence**, when the change is user-visible. Use the screenshots already
   saved during verification (`ios-NN-<step>.png` / `android-NN-<step>.png`
   in the session scratchpad's `evidence/`); do not re-record. Capture only
   the steps that are missing. Then publish and comment:

   ```bash
   scripts/publish-evidence.sh <number> <scratchpad>/evidence > <scratchpad>/evidence.md
   ```

   ```bash
   gh pr comment <pr> --body-file <scratchpad>/evidence.md
   ```

   Start the comment with one line naming the platforms, protocol, and data
   source (harness or mocks) above the table. Keep captions short; an empty
   platform cell is a gap to name under Risks / Follow-ups. With nothing
   visible, skip this step and keep "No UI change" under Checks.

Name the issue as `#<number>`. Write `Fixes #<number>` whenever the pull
request completes the issue's work, so merging closes it; unticked Remaining
boxes do not block this. Use a bare `#<number>` only when other work on the
issue is still open, and say so under Risks / Follow-ups. Add an issue comment only when asked, via
`github-issue-tracking`. Report the commit, remote branch, pull-request URL,
and evidence comment URL.
