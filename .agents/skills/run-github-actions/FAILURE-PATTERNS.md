# GitHub Actions Failure Patterns

Read this reference only after the exact run and first actionable failure are
known. A matching symptom suggests checks; it is not evidence of the cause by
itself. Current workflow YAML and run logs remain authoritative.

## Android formatting or lint

**Symptoms:** a Gradle task named `ktlintCheck` or `lintDebug` fails.

- Locate the first reported file, line, rule, and message in the failed step.
- Confirm that the named task is present in the workflow at the run's SHA.
- Treat the rule violation as the supported cause only when the task output
  identifies it. Otherwise preserve it as a hypothesis and inspect the report
  artifact or fuller logs.

## Android unit tests

**Symptoms:** `testDebugUnitTest` or an individual JVM test fails.

- Capture the first failing test and assertion or exception.
- Separate an assertion failure from test-process crashes and infrastructure
  failures.
- If a rerun passes, report possible flakiness as a hypothesis unless timing or
  scheduler evidence identifies the mechanism.

## iOS scheme or destination

**Symptoms:** Xcode cannot find the scheme or an eligible simulator destination.

- For a missing scheme, inspect the run's checkout for the shared scheme path
  named by the workflow.
- For destination errors, inspect the simulator-selection step output and the
  destination passed to the failing `xcodebuild` invocation.
- Do not infer a missing simulator from a generic `xcodebuild` exit code alone.

## Swift package resolution

**Symptoms:** dependency resolution, checkout, or package graph construction
fails before build or tests.

- Distinguish network or service errors from an invalid revision, an incompatible
  package graph, and a changed lockfile.
- Check the exact dependency and revision in the log and at the run's SHA before
  recommending a rerun.

## iOS signing

**Symptoms:** the log names code signing, provisioning, certificates, or a team.

- Confirm whether the failing workflow command disables signing.
- Identify which target or build phase still requests signing.
- A signing-related phrase outside the decisive error block is not enough to
  classify signing as the cause.

## Environment-only or transient failures

**Symptoms:** the same command passes locally while CI fails, or logs show runner,
network, cache, disk, or hosted-service errors.

- Compare the run's OS image, toolchain setup, clean checkout, environment,
  cache use, and exact command against the local reproduction.
- Name the differing variable supported by evidence. If none is isolated,
  report an environment difference as a hypothesis, not a cause.
- Recommend a rerun only when the failure is plausibly transient and the user
  has asked for a rerun or recommendation.
