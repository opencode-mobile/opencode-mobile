---
name: documentation-planning
description: Plan and maintain opencode-mobile documentation artifacts, including feature docs, task plans, progress updates, decisions, parity gaps, and companion HTML visual plans. Use when creating or updating plans, deciding where docs should live, adding durable planning artifacts, or documenting implementation progress.
---

# Documentation Planning

Use this skill when creating or updating durable project documentation,
especially feature plans and task-specific plans that should survive handoff.

## Storage Rules

- Store durable feature documentation under `docs/features/<feature-slug>/`.
- Keep feature-wide source docs in:
  - `plan.md`
  - `progress.md`
  - `decisions.md`
  - `parity-gaps.md`
- Store task-specific plans beside the feature docs:
  - `<ticket-or-task>-plan.md`
  - `<ticket-or-task>-plan.html`
- Keep scratch notes, debug dumps, and temporary investigations out of tracked
  feature docs unless they become useful review or handoff material.

## Markdown And HTML Plans

- Markdown is the source of truth when the repo needs canonical planning text.
- HTML is a companion visual review artifact for plans that benefit from layout,
  comparison tables, flow diagrams, or scan-friendly status views.
- When creating a meaningful plan, create or update the HTML companion alongside
  the markdown plan unless the user asks for text only.
- Do not treat HTML plans as runtime assets.
- Commit manually curated HTML plans. If an HTML plan becomes mechanically
  generated later, document the regeneration command and reconsider whether to
  commit generated output.

## Planning Workflow

1. Identify the feature slug and existing docs directory.
2. Read existing `plan.md`, `progress.md`, `decisions.md`, and `parity-gaps.md`
   before changing docs.
3. For a new feature, use `docs/features/README.md` as the structure guide.
4. For task-specific work, add a focused task plan beside the feature docs
   instead of overloading the feature-wide `plan.md`.
5. Keep plans scoped to product behavior, implementation strategy, risks,
   validation, and handoff notes.
6. Update `progress.md` after meaningful implementation progress.
7. Record surprising or durable choices in `decisions.md`; use an ADR only for
   hard-to-reverse project decisions with real trade-offs.
8. Record cross-platform mismatches in `parity-gaps.md` as soon as they are
   discovered.

## HTML Plan Guidance

Use a self-contained `.html` file with inline CSS so it opens directly in a
browser. Prefer:

- a short title and summary;
- status cards for scope, risks, and validation gates;
- tables for artifacts, phases, risks, and test plans;
- compact flow sections for implementation sequence;
- restrained colors that work in light and dark mode.

Avoid:

- marketing-style landing pages;
- decorative-only graphics;
- JavaScript unless interaction is genuinely needed;
- content that diverges from the markdown source of truth.

## Commit Guidance

Commit docs that explain durable scope, implementation strategy, decisions,
progress, or handoff state. Do not commit local scratch artifacts, generated
debug output, or exploratory notes that are not useful to future contributors.

## Skill Coordination

- Use with `synced-native-feature` for feature slices that span iOS and Android.
- Use with `design-system-component` when documentation adds or changes reusable
  UI components, tokens, variants, previews, or galleries.
- Use with `linear-project-tracking` only when the work should also be reflected
  in Linear; documentation planning itself does not require Linear.
