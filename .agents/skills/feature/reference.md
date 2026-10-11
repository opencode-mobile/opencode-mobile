# Feature branch and publication reference

Read only the section the current route names.

## Branch resolution and naming

Resolve a ticket to one GitHub issue number. A legacy `OPE-<number>` is a title
prefix on imported issues. Search `in:title` for that id, then use the GitHub
issue number `gh` returns. `OPE-5` and `#5` are different tickets.

When resolving a branch to a ticket:

1. Parse the current branch for an issue key.

   Take the first case-insensitive match of `ope[-_/]<number>` as a legacy key.
   When the branch has no legacy key, take the first number in
   `feature/<number>-` or `fix/<number>-` as the GitHub issue number.

   **Completion criterion:** The branch yields one legacy key, one GitHub issue
   number, or no match.

2. If the branch has no issue key, inspect recent commits for `#<number>`, a
   legacy `OPE-<number>`, or a feature title.

   **Completion criterion:** Relevant recent commits yield one issue number, one
   legacy key, one search title, or contain no ticket evidence.

3. Resolve a legacy key or a title with
   `gh issue list --state all --search`. Include `in:title` for a legacy key.
   Confirm a bare number with `gh issue view`. A pull request at that number is
   a different object.

   **Completion criterion:** Current GitHub results identify one issue, multiple
   candidates, or no candidate.

4. If evidence identifies multiple issues or none, ask for the issue before
   ticket-linked mutations.

   **Completion criterion:** Branch resolution has one GitHub issue number, or
   ticket-linked mutation is paused with the missing evidence named.

When starting from a ticket, prefer an existing local issue branch. Check remote
branches before creating one when remote state matters to the request. Name a
new branch per `AGENTS.md` **Branch naming**.

If the issue title starts with `OPE-<number>:`, use:

```text
feature/ope-<number>-<feature-slug-or-short-title>
```

Otherwise use:

```text
feature/<number>-<feature-slug-or-short-title>
```

Use `fix/` instead of `feature/` when the ticket is a bug. The branch starts
with `feature/` or `fix/`.

The slug uses lowercase ASCII letters or numbers separated by single hyphens,
with no leading or trailing hyphen, and the full branch name at 80 characters
or fewer. Strip punctuation. Leave the slug as words.

**Completion criterion:** Branch resolution produces one GitHub issue number,
or reports that local and requested remote evidence is insufficient. Every new
branch matches the format and length rules exactly.

## Merge readiness

Evaluate these invariants only when they apply:

- the branch name contains the issue key for that ticket: `ope-<number>` when
  the title starts with that legacy id, otherwise the GitHub issue number
- the diff contains only the intended feature scope and leaves unrelated user
  changes alone
- planned, completed, and remaining work agree with the GitHub issue when
  ticket state is part of the request
- relevant automated checks have a recorded pass, fail, or explicit skip reason
- the pull-request summary, when one exists, states scope, automated checks, and
  remaining risks

**Completion criterion:** Every applicable invariant has current evidence and a
`pass`, `fail`, or `not applicable` result. The readiness summary includes every
failed invariant.

## Publication steps

Use this only when the user requested publication. A harness-provided GitHub
publish tool may perform steps 2–5, but the result must match them:

1. Review the current branch, status, and diff.

   **Completion criterion:** The branch, staged state, unstaged state, and every
   changed path are accounted for before staging.

2. Stage only files belonging to the requested feature.

   **Completion criterion:** Every staged path belongs to the requested feature,
   and every unrelated path remains unstaged.

3. Commit with the GitHub issue number and concise scope, for example
   `#367 Add theme token previews`.

   **Completion criterion:** The commit succeeds and its identifier and
   issue-keyed subject are recorded.

4. Push the current branch.

   **Completion criterion:** The exact local commit is present on the named
   remote branch.

5. Open a draft pull request unless the user explicitly requests a ready pull
   request. Use this body shape, omitting empty optional sections:

```markdown
## Summary
- ...

## Checks
- ...

## Issue
- #<number>

## Risks / Follow-ups
- ...
```

   Name the issue as `#<number>`. Write `Fixes #<number>` only when this pull
   request finishes the issue.

   **Completion criterion:** The pull request has the requested draft state,
   correct base and head branches, the required non-empty body sections, and a
   direct URL.

6. The `#<number>` mention in the pull request body is the cross-reference.
   When the user asked for an issue comment, add it with `github-issue-tracking`
   and read the issue again.

   **Completion criterion:** The pull request body names the GitHub issue. Every
   requested issue comment is confirmed from current issue state or named as
   missing.
