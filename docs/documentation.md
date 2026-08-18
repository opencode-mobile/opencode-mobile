# opencode-mobile Documentation System

This document maps how the repository's documentation is **structured**, **used**,
**linked from agent guidance and skills**, and **created**. It is the artifact to
consult when the question is "where does this fact live, who owns it, and how do
I update it?".

---

## 1. Documentation anatomy — where everything lives

```text
/
├── README.md                      # entry point: layout, dev commands, CI table, doc index
├── CONTEXT.md                     # domain glossary + product terms (single-context layout)
├── AGENTS.md                      # agent & contributor guide (hub for skills + doc rules)
├── CLAUDE.md -> AGENTS.md         # symlink (same content)
├── docs/
│   ├── architecture.md            # native app architecture + layer shape + parity rules
│   ├── feasibility.md             # server-API evidence
│   ├── mac-mini-runner.md         # iOS CI self-hosted runner ops
│   ├── adr/                       # 6 architectural decision records (0001–0006)
│   ├── agents/                    # agent-facing how-to docs
│   │   ├── domain.md              #   how skills consume CONTEXT.md + docs/adr
│   │   ├── issue-tracker.md       #   Linear + GitHub conventions
│   │   └── triage-labels.md       #   canonical triage label mapping
│   ├── design-system/             # canonical design-system planning source
│   │   ├── tokens.md              #   4-level token system + resolved token tables
│   │   ├── typography.md          #   semantic type roles + platform mappings
│   │   ├── icons.md               #   Material icon catalog + sync workflow
│   │   ├── opencode-web-theme.md  #   web theme evidence
│   │   ├── app-icon.md            #   launcher + store listing icons
│   │   ├── divergences.md         #   intentional platform differences (persistent)
│   │   └── components/            #   Component Collection contract + docs
│   │       ├── README.md          #     component contract, template, gallery, DoD
│   │       └── <name>.md          #     34 component docs (button, list-item, …)
│   └── features/                  # synchronized Feature Slices (one dir per feature)
│       ├── README.md              #   canonical schema — single source of truth
│       └── <feature-slug>/        #   one directory per feature
│           ├── plan.md            #   current state (goal, now, remaining)
│           ├── decisions.md       #   still-binding choices only
│           └── parity-gaps.md     #   open iOS/Android gaps only
└── .agents/
    └── skills/                    # canonical skills (source of truth)
        ├── design-system-component/SKILL.md
        ├── feature/{SKILL.md, reference.md, agents/}
        ├── linear-project-tracking/{SKILL.md, reference.md}
        ├── pull-app-logs/{SKILL.md, ANDROID.md, IOS.md, TROUBLESHOOTING.md}
        ├── publish-prototype/SKILL.md
        ├── publish-ui-snapshots/{SKILL.md, reference.md, scripts/}
        ├── run-github-actions/{SKILL.md, FAILURE-PATTERNS.md}
        └── synced-native-feature/SKILL.md
```

Feature docs are lean: `plan.md` is current state. No progress diaries,
ticket plans, or research dumps. History is git.

---

## 2. How the documentation is structured

### 2.1 Feature slices — the core unit

`docs/features/README.md` is the schema. Each feature directory has:

| Artifact | Purpose |
| --- | --- |
| `plan.md` | Current state: goal, what shipped, remaining work, APIs that still matter. |
| `decisions.md` | Still-binding choices only. Reverse by replacing. |
| `parity-gaps.md` | Open iOS/Android gaps only. Omit if none. |

Do not commit `progress.md`, ticket plans, research dumps, web-specs, or soak
reports. Optional ops files (`operator-runbook.md`, `ruleset.md`) stay if still
run. Markdown only.

### 2.2 Design system

`docs/design-system/` is the canonical planning source for reusable UI:

- `tokens.md` — four-level token system: primitive palette seeds → base semantic
  tokens → component tokens → platform mappings.
- `typography.md` — semantic roles (`type.display` … `type.button`) with shared
  meaning and per-platform mapping; no raw sizes in public APIs.
- `icons.md` — shared Material icon catalog driven by `icons/manifest.yaml` plus
  the sync/audit workflow.
- `divergences.md` — **intentional, persistent** platform differences, each with
  a template (status, area, shared intent, iOS/Android behavior, reason, review
  trigger). Never used for unfinished work.
- `components/README.md` — the Component Contract (variant-first, state-aware, no
  raw hex/gradients/radius in public APIs), the component doc template, the
  Gallery And Preview Contract, and the Definition Of Done.
- `components/<name>.md` — one doc per reusable component using the template:
  Intent, Public API (variants/states/content), Token Usage, Behavior, Platform
  Notes, Review Surfaces, Parity And Divergence.

### 2.3 ADRs and domain

- `docs/adr/NNNN-<slug>.md` — used only for decisions that are hard to reverse,
  surprising without context, and the result of a real trade-off. Format:
  Status / Context / Decision / Consequences / Review Triggers.
- `CONTEXT.md` — the domain glossary. Single-context layout: product terms with
  definitions plus explicit `_Avoid:_` synonyms, so agents and docs stay on one
  vocabulary. `docs/agents/domain.md` tells skills to read `CONTEXT.md` and
  relevant ADRs before exploring, and to flag ADR conflicts instead of silently
  overriding them.

### 2.4 No HTML companions

Committed HTML plan companions are retired. Plans and feature docs are Markdown
only; render a temporary visual review on demand when one is needed instead of
committing an HTML file. Markdown is the source of truth.

---

## 3. How the documentation is used

1. **Agents are the primary consumers.** Skills read canonical docs before any
   mutation. `synced-native-feature` reads `docs/features/README.md` sections per
   phase; `design-system-component` reads the token/typography/component
    references; `feature` reads `plan.md` / `decisions.md` / `parity-gaps.md`
    per `docs/features/README.md`.

2. **Linear is the ticket.** Refine issues in Linear. Repo `plan.md` is
    only the durable product contract, not a ticket mirror.

3. **Architecture docs feed the apps.** `docs/architecture.md` defines the layer
   shape; its mermaid diagram shows Feature Docs + Design System Docs feeding the
   iOS/Android Screen Models. `docs/feasibility.md` records which server endpoints
   back each v1 behavior.

4. **CI and release operators.** `docs/features/ci-pr-gating/ruleset.md`,
   `operator-runbook.md` files, and `docs/mac-mini-runner.md` carry the
   non-secret operating contract for workflows, secrets (by name, never value),
   and recovery.

5. **README is the index.** Top-level `README.md` links `CONTEXT.md`, `AGENTS.md`,
   `docs/architecture.md`, `docs/design-system/`, `docs/features/`, plus scripts
   and CI tables.

---

## 4. How documentation is linked from agent files and skills

### 4.1 The hub: `AGENTS.md`

`AGENTS.md` (mirrored by `CLAUDE.md`) is the single agent-facing hub. It:

- points to each skill with a one-line usage trigger (see **Skill Usage**);
- codifies the **Documentation Rules**: `CONTEXT.md` for domain terms,
  `docs/feasibility.md` for feasibility, `docs/architecture.md` for architecture,
  `docs/design-system/` for design-system planning, `docs/features/<slug>/` for
  feature progress/decisions/parity;
- requires durable docs committed, while scratch notes stay out of git;
- reserves ADRs for hard-to-reverse decisions;
- keeps tooling-specific paths as symlinks so skills are shared across agents:
  `.cursor/skills` → `.agents/skills`, `.claude/skills` → `.agents/skills`,
  `CLAUDE.md` → `AGENTS.md` (recreated by `./scripts/sync-agent-links.sh`);
- records branch naming (`feature/ope-<number>-<slug>`), issue-tracker usage, and
  design-system workflow ordering.

### 4.2 The skill graph and ownership

Seven skills live under `.agents/skills/<name>/SKILL.md` (plus per-skill
reference files). Ownership boundaries are explicit:

| Skill | Owns | Delegates to / is delegated by |
| --- | --- | --- |
| `synced-native-feature` | Cross-platform Feature Slice planning and parity | Docs follow `docs/features/README.md`; reusable UI → `design-system-component` |
| `design-system-component` | Design-system tokens, typography, components, variants, previews, galleries, divergences | Reads `docs/design-system/*`; feature work delegates reusable-UI needs to it |
| `linear-project-tracking` | Linear issue/status/link mutations and reconciliation with docs | Reads docs; creates `plan.md` if missing |
| `feature` | Resolving anchors (Linear ↔ branch ↔ docs ↔ PR) and running Start/Docs/Merge/Publish routes | Dispatches each mutation to its owner; owns branch creation/switching |
| `run-github-actions` | Running/monitoring/diagnosing GitHub Actions | Reads `.github/workflows/*.yml` as executable authority; no canonical docs |
| `pull-app-logs` | Reading Android logcat / iOS simulator logs | Operational; references platform docs (`ANDROID.md`, `IOS.md`, `TROUBLESHOOTING.md`) |

Coordination rules that keep docs and skills consistent:

- Feature docs follow `docs/features/README.md`. Repository documentation
  does not require Linear.
- `feature` is a router: it never mutates state itself; it reads owning-skill
  state after a mutation and verifies the requested link (issue, docs path,
  branch, PR).
- `synced-native-feature` blocks dependent feature work until a delegated
  `design-system-component` ledger item is complete, recording blockers in
  feature progress.
- `linear-project-tracking` reconciles only when docs changed first, and never
  copies undocumented Linear scope into the repo.

### 4.3 Anchor cross-linking

A ticket-backed feature is kept connected across four anchors:
Linear issue key (`OPE-<number>`) ↔ branch
(`feature/ope-<number>-<slug>`) ↔ docs directory
(`docs/features/<feature-slug>/`) ↔ pull request. `feature/reference.md`
defines branch parsing, merge-readiness invariants, and the PR body shape that
carries the `## Docs` path back to Linear.

---

## 5. How the documentation is created

### 5.1 Canonical workflow

Follow [`docs/features/README.md`](features/README.md): rewrite `plan.md` as
current state; add `decisions.md` / `parity-gaps.md` only when they still
constrain work. No progress logs, ticket plans, or research dumps. Markdown
only. Scratch notes stay out of git.

Completion gate: every routed artifact is `updated`, `unchanged with a reason`,
`not applicable with a reason`, or `blocked with a named dependency`; Markdown
artifacts agree; relative links resolve; the final
diff is only intended durable documentation.

### 5.2 Feature Slice creation: `synced-native-feature`

1. Close the shared plan before implementation (populate every `plan.md` field).
2. Classify reusable-UI needs (existing component / create-extend / feature-local)
   and delegate to `design-system-component` when missing.
3. Implement shared behavior on both platforms, updating canonical progress as
   durable status only.
4. Classify differences and decisions as they appear (parity-gaps vs divergences).
5. Account for affected checks (build/lint/test/preview) — report in handoff, not
   in canonical docs.
6. Close the Feature Slice ledger: every item complete / not-applicable-with-
    reason / blocked-with-evidence.

### 5.3 Design-system creation: `design-system-component`

1. Define the shared contract (semantic meaning + iOS and Android mapping) before
   implementation.
2. Update canonical design-system documents before dependent code.
3. Implement native changes aligned to the shared contract.
4. Add review surfaces (gallery entries + previews) per the Gallery And Preview
   Contract.
5. Classify every platform difference (divergences.md for intentional, feature
   parity-gaps.md for temporary).
6. Close the completion ledger; run the affected build/lint/test/preview checks.

### 5.4 Linear: `linear-project-tracking`

Linear owns the ticket (detail, refinement, remaining, status). Repo docs
own the durable product contract only. Do not create markdown to keep Linear
“in sync.”

### 5.5 Generated docs and scripts

- **Synced/generated artifacts:** `scripts/` keep platform copies aligned:
  `npm run icons:sync`, `npm run themes:sync` (OC-2 desktop presets →
  `shared/theme-presets/` + iOS/Android bundles), `themes:check` and `icons:check`
  verify parity, `api:fetch`/`api:generate` pull the OpenAPI spec and generate
  clients. Preset updates are deliberate (the sync scripts pin an opencode git
  ref).

### 5.6 Delivery path for a feature slice

`feature` links the ticket, branch, docs directory, and PR. Publishing follows
`feature/reference.md`: review branch/status/diff, stage only feature files,
commit with the issue key, push, open a draft PR whose body carries `## Summary`,
`## Docs` (canonical docs path), `## Checks`, `## Linear`, `## Risks / Follow-ups`,
then add the PR backlink to Linear and verify the cross-references.

---

## 6. Quick reference — artifact → owner → schema

| Fact type | Canonical location | Owning skill | Schema authority |
| --- | --- | --- | --- |
| Domain term | `CONTEXT.md` | `domain-modeling` (via `docs/agents/domain.md`) | glossary convention |
| Product architecture | `docs/architecture.md` | — (read before changes) | architecture doc itself |
| Server feasibility | `docs/feasibility.md` | — (read before changes) | feasibility doc |
| Hard-to-reverse decision | `docs/adr/NNNN-….md` | — | ADR format |
| Feature intent / remaining | `docs/features/<slug>/plan.md` | `synced-native-feature` | `docs/features/README.md` |
| Cross-app mismatch | `docs/features/<slug>/parity-gaps.md` | `synced-native-feature` | `docs/features/README.md` |
| Feature decision | `docs/features/<slug>/decisions.md` | `synced-native-feature` | `docs/features/README.md` |
| Intentional platform difference | `docs/design-system/divergences.md` | `design-system-component` | divergence template |
| Token / typography | `docs/design-system/{tokens,typography}.md` | `design-system-component` | those documents |
| Component | `docs/design-system/components/<name>.md` | `design-system-component` | `components/README.md` template |
| Release ops | `<slug>/operator-runbook.md` | release workflow | non-secret contract |

---

## 7. Routing cheat sheet — where new facts go

- **Product scope / current state / remaining work** → `docs/features/<slug>/plan.md`
- **A durable feature-level choice** → `docs/features/<slug>/decisions.md`
- **A temporary or intentional platform difference** → feature `parity-gaps.md`
  (temporary) or `docs/design-system/divergences.md` (intentional, persistent)
- **Reusable UI decision** → design-system component workflow, not feature-local
- **A hard-to-reverse, surprising, trade-off decision** → ADR
- **Raw evidence (screenshots, logs, command output)** → never commit; distill the
  durable fact into its canonical artifact
