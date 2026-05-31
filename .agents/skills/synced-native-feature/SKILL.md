---
name: synced-native-feature
description: Develop opencode-mobile Feature Slices across both native apps in sync. Use when planning or implementing a feature for iOS and Android, tracking progress, documenting parity gaps, or coordinating shared product behavior.
---

# Synced Native Feature

Use this skill when planning or implementing a Feature Slice that should exist
in both native apps.

## Ground Rules

- A Feature Slice is one coordinated unit of product work across iOS and
  Android.
- Keep implementation native per platform, but align product intent, domain
  terms, state names, errors, permission behavior, and design-system usage.
- Use existing design-system components first.
- If a feature needs new reusable UI, pause feature implementation and use
  `.agents/design-system-component/SKILL.md`.
- Use custom feature-local UI only after checking for an existing component and
  considering whether the missing UI should become a reusable component.
  Document the reason when custom local UI remains the right choice.
- Document inconsistencies as soon as they are discovered.

## Start The Feature

Create or update:

```text
docs/features/<feature-slug>/
├── plan.md
├── progress.md
├── parity-gaps.md
└── decisions.md
```

Use `docs/features/README.md` as the template source.

## Planning Checklist

Before implementation, document:

- user goal;
- in-scope and out-of-scope behavior;
- server APIs involved;
- domain objects involved;
- Screen Model state;
- user actions and events;
- loading, empty, error, and permission states;
- design-system components needed;
- existing components that will be reused, components that need to be created or
  extended, and any feature-local UI that remains after that check;
- expected iOS and Android navigation entry points.

## Implementation Workflow

1. Update `plan.md` with the shared intent and feature boundary.
2. Update `progress.md` with initial iOS, Android, design-system, and test
   status.
3. Confirm required components already exist in the Component Collection.
4. If reusable UI is missing, switch to the design-system component skill and
   return after it is documented.
5. Use custom feature-local UI only for behavior or layout that is not covered by
   existing components and does not warrant a reusable component yet; document
   that reason in feature docs.
6. Implement the domain adapter, repository/service, Screen Model, and screen on
   both platforms once app projects exist.
7. Keep copy, state names, error categories, and permission behavior aligned.
8. Update `progress.md` after each meaningful platform milestone.
9. Add each mismatch to `parity-gaps.md` immediately.
10. Record local feature decisions in `decisions.md`.
11. Run relevant platform checks when they exist.

## Parity Gap Format

Use this format in `parity-gaps.md`:

```markdown
## <Short Gap Name>

- Status: temporary | intentional
- Area: behavior | state model | copy | visual semantics | token mapping | accessibility | error handling | missing implementation
- Shared intent:
- Current iOS state:
- Current Android state:
- Reason:
- Owner or next action:
```

Move persistent design-system differences to
`docs/design-system/divergences.md`.

## Definition Of Done

- Feature docs exist and are current.
- iOS and Android share the same product intent and state model concepts.
- New reusable UI went through the design-system component workflow.
- Parity gaps are either closed or explicitly marked intentional.
- Both platforms pass relevant checks when app projects exist.
- The feature does not expand v1 scope without updating project docs first.
