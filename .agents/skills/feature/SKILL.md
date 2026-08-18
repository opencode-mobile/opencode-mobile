---
name: feature
description: Link opencode-mobile feature anchors across Linear, branches, docs, and pull requests. Use to resolve a feature from any anchor, start ticket-backed work, locate or update its docs, audit merge readiness, or publish it.
---

# Feature

Keep a feature's issue key, branch, docs directory, and pull request connected.
Route only the work the user requested; do not advance the whole lifecycle by
default.

## Route The Request

Choose one or more routes from the user's request:

| Route | Use when the user wants to | Required anchors |
| --- | --- | --- |
| Resolve | identify or connect existing feature state | the anchors explicitly requested |
| Start | begin ticket-backed implementation | issue key, branch, docs directory |
| Docs | locate, create, or update feature docs | feature slug, docs directory |
| Merge | assess whether existing work is merge-ready | branch, docs directory, issue key when ticket-backed |
| Publish | commit, push, or open a pull request | branch; issue key and docs directory when ticket-backed |

If the request names multiple routes, execute them in dependency order:
Resolve, Start, Docs, Merge, Publish. Do not run a later route merely because it
is visible here.

Completion criterion: every requested action maps to a route, and no
unrequested mutation is included.

## Ownership

This skill resolves anchors and verifies their links. Use the owning skill for
state changes:

- `linear-project-tracking` creates or updates Linear issues, statuses, and
  links.
- `synced-native-feature` owns cross-platform feature planning and parity.
  Docs follow `docs/features/README.md`.
- `design-system-component` owns reusable UI, tokens, variants, previews, and
  galleries.
- GitHub `yeet` owns staging, committing, pushing, and pull-request creation
  when available; this skill's disclosed fallback applies only when it is not.

Branch creation or switching remains part of this skill's Start route. After an
owning skill mutates state, read the resulting state and verify the requested
link.

Completion criterion: each requested mutation is performed by its owner, and
each resulting issue, docs, or publication link is verified from current state.

## Resolve Route

1. Start from the strongest supplied anchor: issue key (`OPE-<number>`), current
   branch, `docs/features/<feature-slug>/`, pull request, or feature name.
2. Resolve only the additional anchors required by the selected route. When a
   branch must be parsed or an anchor must be inferred, read
   [branch and publication reference](reference.md#branch-resolution-and-naming).
3. Prefer existing repository and Linear evidence over a newly inferred slug.
   If required evidence remains ambiguous, report the candidates or ask for the
   missing issue key before making ticket-linked changes.

Completion criterion: every required anchor for the selected route has one
explicit value, or its unresolved state and the evidence needed to resolve it
are reported; no state is changed.

## Start Route

1. Resolve the issue key and inspect the worktree for unrelated changes.
2. Read
   [branch resolution and naming](reference.md#branch-resolution-and-naming),
   then switch to the existing issue branch or create the specified branch.
3. Resolve the existing docs directory without creating a replacement slug.
4. Use `linear-project-tracking` for any requested Linear status or link update.
5. Read the existing feature docs required by the implementation skill before
   editing implementation files.

Completion criterion: the current branch contains the normalized issue key, the
existing docs directory is identified or its absence is explicit, unrelated
worktree changes remain untouched, and each requested Linear mutation is
confirmed from Linear.

## Docs Route

1. Resolve `docs/features/<feature-slug>/`, preferring an existing directory.
2. Read the feature files that exist: `plan.md`, `decisions.md`,
    `parity-gaps.md`.
3. Follow `docs/features/README.md` for docs changes. Use
    `synced-native-feature` when the change concerns shared iOS/Android intent
    or parity; use `design-system-component` when it changes reusable UI
    documentation.
4. Verify that the resulting docs path still resolves from the issue or branch
   anchor used for this request.

Completion criterion: the canonical docs directory is explicit, every requested
docs change satisfies its owning skill's completion criterion, and the resulting
path is linked to the active feature anchor.

## Merge Route

Read [merge-readiness reference](reference.md#merge-readiness) and evaluate every
applicable invariant against current repository and Linear state. Use the owning
skill to repair stale docs or Linear state only when the user asked for updates;
otherwise report the mismatch.

Completion criterion: every applicable merge invariant is marked pass, fail, or
not applicable with concrete evidence, and every failure is listed as a
merge-blocking item or accepted risk.

## Publish Route

Publish only on an explicit commit, push, or pull-request request. Use GitHub
`yeet` when available. If it is unavailable, read and execute
[publication fallback](reference.md#publication-fallback). Use
`linear-project-tracking` for the Linear backlink after a pull request exists.

Completion criterion: each requested publication action is confirmed by commit
identifier, remote branch, or pull-request URL; for ticket-backed work, the pull
request, issue, branch, and docs path cross-reference one another, or the exact
missing link is reported.
