---
name: run-github-actions
description: "GitHub Actions: use to dispatch a workflow, monitor a specific run, or diagnose a failed run."
---

# Run GitHub Actions

Use `gh` from the repository root. The selected file under `.github/workflows/`
is the source of truth for triggers, jobs, runner environment, working
directories, and commands. Read that file before acting. Do not rely on
copied command lists.

Keep a **run identity** throughout the task:

- workflow name or file
- requested ref and resolved head SHA when available
- event and dispatch time for a new run
- run ID and URL once resolved

Resolve one run identity before watching or diagnosing it, then use its ID for
every later command. Never watch or diagnose "the newest run" after candidates
exist. Diagnose by default. Edit code, rerun, cancel, delete, or open a browser
only when the user asks.

## 1. Establish run identity

Confirm `gh auth status` succeeds. Inspect `gh workflow list` and the relevant
workflow YAML. Choose exactly one Route: Dispatch, Monitor, or Diagnose. If the
user supplied a run ID or URL, use it. Otherwise collect enough selectors to
identify one run without guessing.

**Completion criterion:** The selected workflow and Route are explicit, every
known run identity field is recorded, and no remaining candidate can match the
request equally well.

## 2. Dispatch

Select the workflow and ref. Use the current branch when the user did not name
a ref:

```bash
REF="$(git branch --show-current)"
```

If that is empty, for example on detached HEAD, stop and request a ref. Do not
substitute `main`. Confirm the ref exists on GitHub before dispatching.

**Completion criterion:** One workflow and one GitHub ref are selected, the ref
exists remotely, and detached HEAD has not been replaced with an inferred ref.

Record an ISO-8601 UTC dispatch time, then dispatch the selected workflow:

```bash
DISPATCHED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
gh workflow run "<workflow>" --ref "$REF"
```

**Completion criterion:** The dispatch command succeeds, and its workflow, ref,
event, and `DISPATCHED_AT` values are recorded.

Poll runs filtered by workflow, `workflow_dispatch`, and ref. Inspect the
returned `createdAt`, `headSha`, `databaseId`, and `url`. Accept only a run
created at or after `DISPATCHED_AT`.

```bash
gh run list \
  --workflow "<workflow>" \
  --event workflow_dispatch \
  --branch "$REF" \
  --limit 20 \
  --json databaseId,url,workflowName,headBranch,headSha,event,createdAt,status,conclusion
```

If more than one candidate remains, report the ambiguity instead of choosing by
list order.

**Completion criterion:** Exactly one run ID and URL match the workflow, ref,
`workflow_dispatch` event, and dispatch time, or every candidate and missing
selector is reported.

Use only `gh run view <run-id>` or `gh run watch <run-id>` for later work on the
resolved run. Report the run identity, head SHA when available, and whether
monitoring was requested.

**Completion criterion:** Every reported field and later command names the
resolved run ID, and the response includes its direct URL.

## 3. Monitor

Resolve one run ID from the user's ID or URL. Without one, filter by the
workflow, ref, event, and requested time window. Never infer identity from
ordering alone.

**Completion criterion:** One run ID is tied to the supplied anchor or complete
selector set, with no ordering-based inference.

Fetch its identity and state:

```bash
gh run view <run-id> \
  --json databaseId,url,workflowName,headBranch,headSha,event,createdAt,status,conclusion,jobs
```

**Completion criterion:** The current snapshot contains the full run identity,
status or conclusion, jobs, and failed step names when present.

For continuous monitoring, use `gh run watch <run-id>`. For a status check,
return the current snapshot without waiting.

**Completion criterion:** A status check reports the requested snapshot, or
continuous monitoring follows the exact run to a terminal conclusion and
reports every failed job and step name.

## 4. Diagnose

Resolve and report the run identity before reading failure output.

**Completion criterion:** The workflow, ref, head SHA, event, run ID, creation
time, and URL are recorded where available before any cause is proposed.

Inspect the run summary and failed logs:

```bash
gh run view <run-id>
gh run view <run-id> --log-failed
```

**Completion criterion:** The exact failed job and step and the complete
decisive error block are extracted from the resolved run.

Find the first actionable failure. Separate it from later cancellations and
cascading errors. Read workflow YAML from the run's `headSha` when it may differ
from the current working tree.

**Completion criterion:** One first actionable failure is identified, every
later failure is classified as independent, cascading, or canceled, and the
workflow evidence comes from the run's revision.

Classify every conclusion:

- **Observed.** Directly present in run metadata or logs.
- **Supported cause.** Backed by workflow and log evidence.
- **Hypothesis.** Names missing evidence and the next discriminating check.

After extracting the error block, consult
[`FAILURE-PATTERNS.md`](FAILURE-PATTERNS.md) only when the observed symptom
matches a pattern or hypothesis prompts are needed.

**Completion criterion:** Every conclusion has exactly one evidence class, and
every hypothesis includes a validation check. A failure pattern is never used
as cause evidence by itself.

If local reproduction is requested and the platform is available, derive the
working directory and exact command from workflow YAML at the run's SHA. Record
every deliberate local deviation. A passing local run does not disprove a
CI-only failure. Compare runner, toolchain, checkout, and generated state using
workflow and log evidence.

**Completion criterion:** The report states whether local reproduction ran,
its exact command, working directory, deviations, and result, or why it was not
run. The strongest supported cause and every bounded hypothesis remain
consistent with that result.

Fix or retrigger only when requested.

**Completion criterion:** The final diagnosis reports the failed job and step,
decisive error block, strongest evidence-supported explanation, bounded
hypotheses, and no unrequested mutation.

## What to report

Every response includes known run identity fields and a direct run URL once
available. Then report only the selected Route:

- **Dispatch.** Dispatch accepted, resolved run ID, and monitoring disposition.
- **Monitor.** Current or terminal state and failed job/step names, if any.
- **Diagnose.** Observed failure, supported cause, bounded hypotheses, and the
  next evidence-producing action.

If identity remains ambiguous, report the candidate IDs and missing selector.
Do not present any candidate's state as the requested run.
