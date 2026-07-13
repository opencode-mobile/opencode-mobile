# HTML Plan Companions

Read this reference only when creating or updating an HTML companion to a plan.

Use one self-contained `.html` file with inline CSS so it opens directly in a
browser. JavaScript is allowed only when the review genuinely needs interaction.

Prefer the smallest visual structure that makes the plan easier to review:

- a short title and summary;
- status cards for scope, risks, and acceptance gates;
- tables for artifacts, phases, risks, and implementation checks;
- compact flows for implementation sequence; and
- restrained colors with readable light and dark modes.

Avoid marketing-page treatments, decorative-only graphics, runtime
dependencies, and content that is not present in the Markdown source.

Before finishing, compare every claim, status, phase, risk, and gate in the HTML
with the Markdown plan. The companion is complete only when all such items agree
or the HTML intentionally presents a strict visual subset without changing
meaning.

Commit manually curated companions. If a companion becomes mechanically
generated, document its regeneration command beside the source and reassess
whether generated output belongs in git.
