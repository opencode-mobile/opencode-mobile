---
name: feature
description: "Feature: use to resolve anchors, start ticket-backed work, handle requested docs, audit merge readiness, or publish."
---

# Feature

A feature is its Linear issue, git branch, and pull request. Keep those three
pointing at each other. Run only the routes the user asked for.

## 1. Route the request

Pick every route the user asked for:

| Route | Use when the user wants to | Required anchors |
| --- | --- | --- |
| Resolve | identify or connect existing feature state | the anchors they named |
| Start | begin ticket-backed implementation | issue key, branch |
| Docs | locate, create, or update documentation they asked for | requested document |
| Merge | check whether existing work is merge-ready | branch, plus issue key when ticket-backed |
| Publish | commit, push, or open a pull request | branch; plus issue key when ticket-backed |

If they named more than one, run them in this order: Resolve, Start, Docs,
Merge, Publish. A later route in the table is not implied.

**Completion criterion:** Every requested action maps to one route, route order
is explicit, and no unrequested mutation is included.

## Ownership

This skill resolves anchors, changes branches in Start, checks links, and uses
the publication fallback when needed. Other state changes belong to the owning
skill:

- `linear-project-tracking` creates or updates Linear issues, statuses, and
  links.
- `synced-native-feature` owns cross-platform feature implementation and parity.
- `design-system-component` owns reusable UI, tokens, variants, previews, and
  galleries.
- GitHub `yeet` stages, commits, pushes, and opens pull requests when it is
  available. This skill's publication fallback applies only when `yeet` is not.

Branch create or switch stays in this skill's Start route. After an owning
skill mutates state, read the result and verify the requested link.

## 2. Resolve

Start from the strongest supplied anchor: issue key (`OPE-<number>`), current
branch, pull request, or feature name. Resolve only the extra anchors required
by the selected routes. Read
[branch resolution and naming](reference.md#branch-resolution-and-naming) when
a branch must be parsed, inferred, selected, or created.

Prefer current repository and Linear evidence over an inferred slug. If
evidence stays ambiguous, report the candidates or ask for the missing issue
key before ticket-linked mutations.

**Completion criterion:** Every required anchor has one explicit value, or the
result names each unresolved anchor and the evidence needed to settle it. Resolve
changes no state.

## 3. Start

Resolve the issue key and inspect the worktree for unrelated changes. Read
[branch resolution and naming](reference.md#branch-resolution-and-naming), then
switch to the existing issue branch or create the specified branch. Preserve
unrelated worktree changes. Use `linear-project-tracking` for requested Linear
status or link updates, then read Linear to verify them.

**Completion criterion:** The current branch contains the normalized issue key,
all unrelated worktree changes remain untouched, and every requested Linear
mutation is confirmed from Linear.

## 4. Docs

Run Docs only when the user asked for documentation. Resolve and read the
requested document, then make only that change. Keep feature state in Linear
and implementation behavior in code and tests. Do not create feature ledgers,
progress logs, ticket plans, research dumps, generated reports, or checked-in
prototypes.

**Completion criterion:** The requested document contains the requested change,
and no unrelated documentation was created or changed.

## 5. Merge

Read [merge-readiness reference](reference.md#merge-readiness) and evaluate
every applicable invariant against current repository and Linear state. Use the
owning skill to repair Linear state only when the user asked for updates;
otherwise report the mismatch.

**Completion criterion:** Every applicable merge invariant has current evidence
and a `pass`, `fail`, or `not applicable` result. Every failure is named as a
merge blocker or accepted risk.

## 6. Publish

Publish only on an explicit commit, push, or pull-request request. Use GitHub
`yeet` when available. If it is unavailable, read and execute
[publication fallback](reference.md#publication-fallback). Use
`linear-project-tracking` for the Linear backlink after a pull request exists.

**Completion criterion:** Every requested publication action has a commit
identifier, remote branch, or pull-request URL. For ticket-backed work, the pull
request, Linear issue, and branch cross-reference one another, or the exact
missing link is reported.
