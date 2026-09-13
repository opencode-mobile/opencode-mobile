# Feature Branch And Publication Reference

Load only the section named by the active Feature route.

## Branch Resolution And Naming

Normalize issue keys to `OPE-<number>` outside branch names. In branches, accept
the first case-insensitive match of `ope[-_/]<number>` as the issue anchor.

When resolving a branch to a ticket:

1. Parse the current branch for an issue key.
2. If absent, inspect recent commits for an issue key or feature title.
3. Search Linear by that feature title.
4. If evidence identifies multiple issues or none, ask for the issue key before
   ticket-linked mutations.

When starting from a ticket, prefer an existing local issue branch. Check remote
branches before creating one when remote state is relevant to the request. Name
a new branch per `AGENTS.md` **Branch naming**:

```text
feature/ope-<number>-<feature-slug-or-short-title>
```

Use `fix/` instead of `feature/` when the ticket is clearly a bug fix. Do not
use Linear's default `{username}/…` branch suggestion, and do not put agent or
username prefixes in the branch name.

The slug must use lowercase ASCII letters or numbers separated by single
hyphens, contain no leading or trailing hyphen, and keep the full branch name at
80 characters or fewer. Remove punctuation rather than encoding it.

Completion criterion: branch resolution produces one normalized issue key, or
reports that local and requested remote evidence is insufficient; a new branch
matches the format and length rules exactly.

## Merge Readiness

Evaluate these invariants only when applicable to the feature:

- the branch name contains the normalized issue key;
- the diff contains only the intended feature scope and preserves unrelated
  user changes;
- planned, completed, and remaining work agree with Linear when ticket state is
  part of the request;
- relevant automated checks have a recorded pass, fail, or explicit skip reason;
- the pull-request summary, when one exists, states scope, automated checks, and
  remaining risks.

Completion criterion: every applicable invariant has current evidence and a
pass, fail, or not-applicable result; no failed invariant is hidden by the
overall readiness summary.

## Publication Fallback

Use this only when the user requested publication and GitHub `yeet` is
unavailable:

1. Review the current branch, status, and diff.
2. Stage only files belonging to the requested feature.
3. Commit with the issue key and concise scope, for example
   `OPE-17 Add theme token previews`.
4. Push the current branch.
5. Open a draft pull request unless the user explicitly requests a ready pull
   request.
6. Use this body shape, omitting empty optional sections:

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

7. Use `linear-project-tracking` to add the pull-request link to Linear when the
   work is ticket-backed and that mutation was requested.

Completion criterion: the requested commit, push, and pull-request operations
each have a concrete identifier or URL, staged scope matches the reviewed diff,
and every requested backlink is verified or named as missing.
