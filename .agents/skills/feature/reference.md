# Feature branch and publication reference

Read only the section the current route names.

## Branch resolution and naming

Normalize issue keys to `OPE-<number>` outside branch names. In branches, take
the first case-insensitive match of `ope[-_/]<number>` as the issue anchor.

When resolving a branch to a ticket:

1. Parse the current branch for an issue key.

   **Completion criterion:** The first case-insensitive branch match is
   normalized to one `OPE-<number>` key, or the branch has no match.

2. If the branch has no issue key, inspect recent commits for an issue key or
   feature title.

   **Completion criterion:** Relevant recent commits yield one normalized key
   or one search title, or contain no ticket evidence.

3. Search Linear by the feature title when no issue key was found.

   **Completion criterion:** Current Linear results identify one issue, multiple
   candidates, or no candidate.

4. If evidence identifies multiple issues or none, ask for the issue key before
   ticket-linked mutations.

   **Completion criterion:** Branch resolution has one normalized issue key, or
   ticket-linked mutation is paused with the missing evidence named.

When starting from a ticket, prefer an existing local issue branch. Check remote
branches before creating one when remote state matters to the request. Name a
new branch per `AGENTS.md` **Branch naming**:

```text
feature/ope-<number>-<feature-slug-or-short-title>
```

Use `fix/` instead of `feature/` when the ticket is clearly a bug fix. Ignore
Linear's `{username}/…` suggestion. Do not put agent or username prefixes in the
branch name.

The slug uses lowercase ASCII letters or numbers separated by single hyphens,
with no leading or trailing hyphen, and the full branch name at 80 characters
or fewer. Strip punctuation. Do not encode it.

**Completion criterion:** Branch resolution produces one normalized issue key,
or reports that local and requested remote evidence is insufficient. Every new
branch matches the format and length rules exactly.

## Merge readiness

Evaluate these invariants only when they apply:

- the branch name contains the normalized issue key
- the diff contains only the intended feature scope and leaves unrelated user
  changes alone
- planned, completed, and remaining work agree with Linear when ticket state is
  part of the request
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

3. Commit with the issue key and concise scope, for example
   `OPE-17 Add theme token previews`.

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

## Linear
- OPE-<number>

## Risks / Follow-ups
- ...
```

   **Completion criterion:** The pull request has the requested draft state,
   correct base and head branches, the required non-empty body sections, and a
   direct URL.

6. Use `linear-project-tracking` to add the pull-request link to Linear when the
   work is ticket-backed and that mutation was requested.

   **Completion criterion:** Every requested Linear backlink is confirmed from
   current issue state or named as missing.
