---
name: feature
description: Link opencode-mobile feature work across Linear tickets, git branches, docs/features planning artifacts, merge checklists, and pull requests. Use when starting work from a Linear issue, deriving a ticket from the current branch, creating or naming a feature branch, locating the relevant docs/features directory, checking whether code is ready to merge, or preparing a pull request.
---

# Feature

Use this skill to keep one feature thread connected across Linear, branch,
documentation, implementation, verification, and pull request state.

## Orientation

1. Identify the feature anchor from the strongest available signal:
   - Linear issue key, usually `OPE-<number>`;
   - current branch name;
   - feature slug under `docs/features/<feature-slug>/`;
   - user-facing feature name.
2. Resolve the missing anchors from the known ones until these are explicit:
   issue key, issue title, branch name, docs directory, and current task state.
3. Before code changes, read the relevant docs directory and Linear issue.
4. Before finishing, update docs and Linear, verify the change, and prepare a
   pull request summary if the user wants the work published.

Completion criterion: the active feature has one visible chain from issue to
branch to docs to PR or merge-ready handoff.

## Branch And Issue Link

Use `OPE-<number>` as the ticket key. Normalize lowercase branch text such as
`ope-17` back to `OPE-17` when talking to Linear or docs.

### From Branch To Ticket

1. Read the current branch.
2. Extract the first issue key matching `ope[-_/]<number>` or `OPE-<number>`.
3. If no key exists, inspect recent commits and docs paths touched for a feature
   slug, then search Linear by that slug or title.
4. If still unresolved, ask for the Linear issue key before making ticket-linked
   updates.

Completion criterion: either an `OPE-<number>` key is found or the user is asked
for the missing key because local evidence is insufficient.

### From Ticket To Branch

When starting from a Linear issue, create or switch to a branch named:

```text
codex/ope-<number>-<feature-slug-or-short-title>
```

Slug rules:

- lowercase words;
- hyphen separators;
- remove punctuation;
- keep the branch short enough to scan.

If a branch for the issue already exists locally, prefer switching to it instead
of creating another branch. If remote branch state matters, check before naming a
new branch.

Completion criterion: the working branch contains the issue key and a readable
feature slug or title.

## Docs Link

Feature planning artifacts live under:

```text
docs/features/<feature-slug>/
```

Use the feature slug from the branch when it matches an existing docs directory.
When only a Linear issue is known, infer the slug from its title and confirm by
listing `docs/features/`. Prefer existing directories over inventing new slugs.

Read these files when present:

- `plan.md`;
- `progress.md`;
- `decisions.md`;
- `parity-gaps.md`;
- task-specific `<ticket-or-task>-plan.md` files.

Create new feature docs through the `synced-native-feature` and
`documentation-planning` skills. Update `progress.md` after meaningful
implementation progress, and update `parity-gaps.md` or `decisions.md` when the
work introduces a mismatch or durable choice.

Completion criterion: the active issue or branch names the docs directory, and
docs reflect the current state before merge handoff.

## Work Start Checklist

Before implementation:

- confirm issue key, branch, and docs directory;
- move or keep the Linear issue in the appropriate active state;
- read `plan.md` and `progress.md`;
- read `decisions.md` and `parity-gaps.md` if present;
- identify whether `synced-native-feature`, `design-system-component`,
  `documentation-planning`, or `linear-project-tracking` also applies;
- check the worktree so unrelated user changes are not disturbed.

Completion criterion: the task can be described as "working on `OPE-<number>` on
`<branch>` for `docs/features/<slug>`" before files are edited.

## Merge Checklist

Before saying work is merge-ready:

- branch name includes the Linear issue key;
- docs path is linked in the Linear issue or PR body;
- `progress.md` reflects completed and remaining work;
- `decisions.md` and `parity-gaps.md` are current when relevant;
- Linear status and remaining checklist match repository state;
- relevant iOS, Android, shared, docs, or unit checks were run, or skipped with a
  concrete reason;
- screenshots, logs, or manual QA notes are attached or referenced when the
  change affects visible behavior;
- no unrelated user changes are staged or described as part of the feature;
- the PR body states scope, docs path, verification, and remaining risks.

Completion criterion: every item is either done or explicitly called out as a
remaining risk before merge handoff.

## Pull Request Instructions

When the user asks to create a PR, use the GitHub `yeet` skill if available.
Otherwise:

1. Review the diff and current branch.
2. Stage only files belonging to the feature.
3. Commit with the issue key and concise scope, for example:

```text
OPE-17 Add theme token previews
```

4. Push the current branch.
5. Open a draft PR unless the user asks for a ready PR.
6. Use this PR body shape:

```markdown
## Summary
- ...

## Docs
- `docs/features/<feature-slug>/progress.md`

## Verification
- ...

## Linear
- OPE-<number>

## Risks / Follow-ups
- ...
```

7. Link the PR in Linear and link the Linear issue in the PR.

Completion criterion: the PR, Linear issue, branch, and docs path cross-reference
each other.
