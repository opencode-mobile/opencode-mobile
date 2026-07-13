# Issue tracker: Linear

Issues and PRDs for this repository live in Linear.

| Item | Value |
| --- | --- |
| Team | `Opencode-mobile` (key `OPE`) |
| Project | `opencode apps` |
| Project URL | <https://linear.app/opencode-mobile/project/opencode-apps-f7d80fb00b5a> |

Use the available Linear app or connector to read and mutate issues. Query
Linear for current state rather than relying on a remembered issue inventory.

## Conventions

- Use product-language titles such as `Session Chat`, not filesystem slugs.
- Link feature issues to their canonical documentation under
  `docs/features/<feature-slug>/`.
- Keep one parent issue per cross-platform feature slice unless the docs record
  an intentional split.
- Keep incomplete canonical work under **Remaining** or in a linked child issue,
  but not both.
- Linear owns scheduling, assignment, priority, status, dependencies, and a
  concise projection of remaining work.
- Repository documentation owns product scope, implementation progress, parity
  gaps, decisions, and intentional platform differences.
- Update canonical documentation before synchronizing a scope or progress
  change to Linear.
- Use the repository's `linear-project-tracking` skill for detailed
  reconciliation rules.

## GitHub operations

Use the `gh` CLI for GitHub repository, pull-request, review, check, release,
and GitHub Actions operations. GitHub Issues are not this repository's issue
tracker.

Infer the GitHub repository from `git remote -v`; `gh` does this automatically
when run inside the clone.

## When a skill says "publish to the issue tracker"

Create an issue in the Linear `Opencode-mobile` team and `opencode apps`
project, following the repository's Linear conventions.

## When a skill says "fetch the relevant ticket"

Read the referenced `OPE-<number>` issue from Linear, including its description,
state, relationships, and comments when relevant.

## Wayfinding operations

The map is one Linear issue with related or child issues representing tickets.

- **Map:** one issue holding Notes, Decisions-so-far, and Fog.
- **Child ticket:** a child or related Linear issue categorized as research,
  prototype, grilling, or task.
- **Blocking:** use Linear dependency relationships.
- **Frontier:** choose the first open, unblocked, and unassigned child.
- **Claim:** assign the child to the acting developer before beginning work.
- **Resolve:** record the answer, complete the child, and add a durable context
  pointer to the map.
