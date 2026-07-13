---
name: run-github-actions
description: "Run opencode-mobile GitHub Actions with `gh`: dispatch a workflow, monitor a specific run, or diagnose a failed run."
---

# Run GitHub Actions

Use `gh` from the repository root. Treat the selected file under
`.github/workflows/` as the executable authority for triggers, jobs, runner
environment, working directories, and commands. Read that file before acting;
do not rely on copied command lists.

Keep a **run identity** throughout the task:

- workflow name or file;
- requested ref and resolved head SHA when available;
- event and dispatch time for a new run;
- run ID and URL once resolved.

Never watch or diagnose “the newest run” after candidates exist. Resolve one
run identity first, and use its ID for every later command. Diagnose by default;
edit code, rerun, cancel, delete, or open a browser only when the user asks.

## Route the request

1. Confirm `gh auth status` succeeds and inspect `gh workflow list` plus the
   relevant workflow YAML.
2. Choose exactly one branch below: **dispatch**, **monitor**, or **diagnose**.
3. If the user supplied a run ID or URL, use it. Otherwise collect enough
   selectors to identify one run without guessing.

This step is complete when the workflow or run is unambiguous and the selected
branch matches the requested action.

## Dispatch

1. Select the workflow and ref. Use the current branch when the user did not
   name a ref:

   ```bash
   REF="$(git branch --show-current)"
   ```

   If that is empty (for example, detached HEAD), stop and request a ref. Do not
   substitute `main`. Confirm the ref exists on GitHub before dispatching.

2. Record an ISO-8601 UTC dispatch time, then dispatch the selected workflow:

   ```bash
   DISPATCHED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
   gh workflow run "<workflow>" --ref "$REF"
   ```

3. Poll runs filtered by workflow, `workflow_dispatch`, and ref. Inspect the
   returned `createdAt`, `headSha`, `databaseId`, and `url`; accept only a run
   created at or after `DISPATCHED_AT`. If more than one candidate remains,
   report the ambiguity instead of choosing by list order.

   ```bash
   gh run list \
     --workflow "<workflow>" \
     --event workflow_dispatch \
     --branch "$REF" \
     --limit 20 \
     --json databaseId,url,workflowName,headBranch,headSha,event,createdAt,status,conclusion
   ```

4. From this point forward, address the resolved run only as
   `gh run view <run-id>` or `gh run watch <run-id>`.

Dispatch is complete when exactly one run ID and URL are tied to the selected
workflow, ref, `workflow_dispatch` event, and dispatch time. Report all of those
fields, the head SHA if available, and whether monitoring was requested.

## Monitor

1. Resolve one run ID from the user's ID or URL. Without one, filter by the
   workflow, ref, event, and time window the user means; never infer identity
   from ordering alone.
2. Fetch its identity and state:

   ```bash
   gh run view <run-id> \
     --json databaseId,url,workflowName,headBranch,headSha,event,createdAt,status,conclusion,jobs
   ```

3. For continuous monitoring, use `gh run watch <run-id>`. For a status check,
   return the current snapshot without waiting.

Monitoring is complete when the requested snapshot is reported or the exact
run reaches a terminal conclusion. Report the run identity, status or
conclusion, and any failed job and step names.

## Diagnose

1. Resolve and report the run identity before reading failure output.
2. Inspect the run summary and failed logs:

   ```bash
   gh run view <run-id>
   gh run view <run-id> --log-failed
   ```

3. Find the first actionable failure, separating it from later cancellations
   and cascading errors. Read the workflow YAML from the run's `headSha`, not
   merely the current working tree, when the two may differ.
4. Classify every conclusion:
   - **Observed:** directly present in the run metadata or log.
   - **Supported cause:** an explanation backed by the workflow and log evidence.
   - **Hypothesis:** a plausible explanation whose missing evidence and next
     discriminating check are stated.
5. After extracting the failing error block, consult
   [`FAILURE-PATTERNS.md`](FAILURE-PATTERNS.md) only when the observed symptom
   matches a pattern or you need hypothesis prompts.
6. If local reproduction is within the user's request and the platform is
   available, derive the working directory and exact command from the workflow
   at the run's SHA. Record any deliberate local deviation. A passing local run
   does not disprove a CI-only failure; compare runner, toolchain, checkout, and
   generated state using evidence from the workflow and logs.

Diagnosis is complete when the exact failed job and step, the decisive error
block, and the strongest evidence-supported explanation are reported. Include
hypotheses only with a validation check. State whether local reproduction was
run and its result. Do not fix or retrigger unless requested.

## Report contract

Every response includes the run identity fields currently known and a direct
run URL once available. Then report only the selected branch's result:

- **Dispatch:** dispatch accepted, resolved run ID, and monitoring disposition.
- **Monitor:** current or terminal state and failed job/step names, if any.
- **Diagnose:** observed failure, supported cause, bounded hypotheses, and the
  next evidence-producing action.

If identity remains ambiguous, report the candidate IDs and missing selector;
do not present any candidate's state as the requested run.
