---
name: run-github-actions
description: "GitHub Actions: use to dispatch a workflow, monitor a specific run, or diagnose a failed run."
---

# Run GitHub Actions

Use `gh` from the repository root. The workflow file under
`.github/workflows/` is the source of truth for triggers, jobs, runners,
working directories, and commands; read it before acting, from the run's
`headSha` when that may differ from the working tree. Diagnose by default;
edit code, rerun, cancel, or open a browser only when asked.

## Pin one run

Always work against one run ID. Never pick "the newest run" once several
candidates exist; if identity stays ambiguous, report the candidates and the
missing selector. Use `gh run view <run-id>` / `gh run watch <run-id>` for all
later work and include the run URL in the report.

## Dispatch

Use the current branch unless the user named a ref. On detached HEAD, stop and
ask; do not substitute `main`. Confirm the ref exists remotely, then record the
dispatch time and find the run it produced:

```bash
REF="$(git branch --show-current)"
DISPATCHED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
gh workflow run "<workflow>" --ref "$REF"
gh run list --workflow "<workflow>" --event workflow_dispatch --branch "$REF" \
  --limit 20 --json databaseId,url,headSha,createdAt,status,conclusion
```

Accept only a run created at or after `DISPATCHED_AT`.

## Monitor

```bash
gh run view <run-id> --json databaseId,url,workflowName,headBranch,headSha,event,createdAt,status,conclusion,jobs
gh run watch <run-id>
```

## Diagnose

```bash
gh run view <run-id>
gh run view <run-id> --log-failed
```

Find the first actionable failure and separate it from cascading errors and
cancellations. Report what the logs show, the cause the evidence supports, and
any hypothesis with the check that would confirm it. A passing local run does
not disprove a CI-only failure; compare runner, toolchain, checkout, and
generated state before calling it transient. Recommend a rerun only when the
failure is plausibly transient.

Project-specific notes: Android Checks soft-fails `ktlintCheck` / `lintDebug`
and hard-fails unit tests; a few coroutine-timing tests are flaky (see
`AGENTS.md`). iOS workflows select the simulator with
`.github/scripts/select-ios-simulator.sh`, so check that step's output before
blaming a destination error on Xcode.
