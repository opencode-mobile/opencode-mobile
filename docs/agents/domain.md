# Domain Docs

How the engineering skills should consume this repository's domain
documentation when exploring the codebase.

## Before exploring, read these

- `CONTEXT.md` at the repository root.
- `docs/adr/` entries that touch the area being changed.

If either location does not exist, proceed silently. Do not suggest creating it
upfront. Domain-modeling workflows create documentation lazily when terms or
decisions are resolved.

## File structure

This repository uses a single-context layout:

```text
/
├── CONTEXT.md
├── docs/
│   └── adr/
└── …
```

## Use the glossary's vocabulary

When output names a domain concept—in an issue title, refactor proposal,
hypothesis, or test name—use the term defined in `CONTEXT.md`. Do not drift to
synonyms the glossary explicitly avoids.

If a needed concept is absent, reconsider whether the language belongs to the
project or note a genuine domain-modeling gap.

## Flag ADR conflicts

If proposed work contradicts an existing ADR, surface the conflict explicitly
instead of silently overriding the decision.
