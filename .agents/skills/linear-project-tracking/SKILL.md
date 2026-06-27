---
name: linear-project-tracking
description: Keep opencode-mobile Linear issues in sync with repo feature docs and implementation work. Use when planning features, implementing changes, updating docs/features progress, closing todos, starting or finishing work, or when the user mentions Linear, issues, backlog, or project tracking.
---

# Linear Project Tracking

All product and implementation work for this repo should be reflected in Linear.
`docs/features/<slug>/progress.md` is the detailed source of truth; Linear is the
execution board.

## Linear Workspace

| Item | Value |
| --- | --- |
| Team | `Opencode-mobile` (key `OPE`) |
| Project | `opencode apps` |
| Status doc | [Implementation Status](https://linear.app/opencode-mobile/document/implementation-status-d505a23348e6) |
| Project URL | https://linear.app/opencode-mobile/project/opencode-apps-f7d80fb00b5a |

Authenticate the Linear MCP server (`plugin-linear-linear`) with `mcp_auth` if
tools are unavailable.

## Milestones

Map feature slices to project milestones:

| Milestone | Feature slices |
| --- | --- |
| V1 Foundations | `server-connection`, `project-list-home`, `project-selection`, `session-repository`, `message-repository`, `foreground-event-stream` |
| Session Chat & Control | `session-chat`, `model-selection`, `session-context`, `clear-data`, permission UI, session CRUD/fork, abort |
| Rendered Markdown | `native-rendered-markdown`, `rendered-markdown` (superseded), `ios-oniguruma-backend`, session diff viewer |
| Polish & Validation | `theme`, `sync-probe`, manual QA |

For milestone-to-issue mapping and label conventions, see
[reference.md](reference.md).

## Core Rule

**Never finish feature work without updating Linear.**

When you change code or docs for a feature slice:

1. Update `docs/features/<slug>/progress.md` (and `parity-gaps.md` if needed).
2. Update the matching Linear issue in the same session.
3. If no issue exists, create one before or as part of the work.

## Workflow

### Before starting work

1. Read `docs/features/<slug>/progress.md` and `plan.md`.
2. `list_issues` filtered by project `opencode apps`; find the issue for this
   slice (title usually matches the feature name).
3. If missing, `save_issue` with:
   - `team`: `Opencode-mobile`
   - `project`: `opencode apps`
   - `milestone`: from table above
   - `labels`: `Feature` plus `iOS`, `Android`, and/or `Shared` as appropriate
   - `description`: user goal, doc path, remaining checklist
4. Move issue to **In Progress** (`state`: `In Progress`).

### While implementing

- Add sub-items to the issue description when scope grows.
- Link related issues with `relatedTo` / `blockedBy` when dependencies exist.
- For cross-platform slices, keep one issue per feature slice (not separate iOS
  and Android issues unless the user asks).

### After completing work

1. Mark checklist items done in `progress.md`.
2. Update the Linear issue:
   - **Done** when the slice is complete for both platforms (or intentionally
     scoped subset is done and remainder is split to a new issue).
   - **In Progress** when meaningful work landed but docs list remaining items.
   - **Todo** when planned but not started.
3. Move remaining items from `progress.md` into the issue description under
   **Remaining**.
4. Post a project status update (`save_status_update`) when a milestone
   materially advances or a major slice ships.

### New feature slice

1. Create docs under `docs/features/<slug>/` (use `synced-native-feature` skill).
2. Create a Linear issue in **Todo** or **Backlog**.
3. Add a row to the Implementation Status document (`save_document` on the
   existing doc).

### Manual QA / verification

Use issue **OPE-20** (Manual QA) or create focused **Manual QA**-labeled
issues. Check off items in the issue description as validation completes.

## Issue Conventions

**Title:** Product name, not slug — e.g. `Session Chat`, `Native Rendered Markdown`.

**Description template:**

```markdown
[One-line summary of the slice.]

**Remaining:**
- [ ] item from progress.md

Docs: `docs/features/<slug>/progress.md`
```

**States:**

| Repo signal | Linear state |
| --- | --- |
| Not planned / V1 backlog | Backlog |
| Planned, not started | Todo |
| Active development | In Progress |
| Shipped per progress.md | Done |
| Superseded or abandoned | Canceled |

**Priority:** High for V1 blockers and in-progress slices; Medium for polish; Low
for follow-ups and optional items.

## What To Sync From Docs

From each `progress.md`, mirror into Linear:

- Completed platform milestones → mark issue Done or trim **Remaining**
- Unchecked `- [ ]` items → issue **Remaining** or child Todo issues
- "Remaining Work" / "Remaining" sections → issue description
- New parity gaps → comment on issue or create Improvement-labeled follow-up
- Verification gaps → Manual QA issue or checklist on the feature issue

## MCP Quick Reference

| Action | Tool |
| --- | --- |
| Find work | `list_issues` with `project: opencode apps` |
| Create/update issue | `save_issue` |
| Project overview | `get_project`, `save_project` |
| Status snapshot | `save_document` on Implementation Status |
| Progress broadcast | `save_status_update` with `type: project`, `health: onTrack` |
| Labels | `create_issue_label` only when a new category is needed |

Always pass markdown with real newlines, not `\n` escape sequences.

## Integration

- **Feature implementation:** use with `synced-native-feature` — docs first,
  then Linear, then code.
- **Design-system work:** track under the relevant feature issue or create an
  Improvement issue linked to the parent feature.
- **Do not** duplicate planning in Linear alone; docs remain canonical for
  decisions, parity gaps, and ADR-worthy trade-offs.

## Checklist (copy per task)

```
- [ ] Read docs/features/<slug>/progress.md
- [ ] Find or create Linear issue in opencode apps
- [ ] Set state to In Progress before coding
- [ ] Implement + update progress.md
- [ ] Update Linear issue state and Remaining list
- [ ] Post project status update if milestone-level progress
```
