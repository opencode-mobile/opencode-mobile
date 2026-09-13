# GitHub Actions failure patterns

Read this only after the exact run and first actionable failure are known. A
matching symptom selects a branch below. It is not cause evidence. Current
workflow YAML at the run's SHA and the resolved run logs are authoritative.

## Android formatting or lint

A Gradle task named `ktlintCheck` or `lintDebug` fails.

- Locate the first reported file, line, rule, and message in the failed step.
- Confirm that the named task is present in the workflow at the run's SHA.
- Use the rule violation as the Supported cause only when task output identifies
  it. Otherwise classify it as a Hypothesis and inspect the report artifact or
  fuller logs.

## Android unit tests

`testDebugUnitTest` or an individual JVM test fails.

- Capture the first failing test and assertion or exception.
- Separate an assertion failure from test-process crashes and infrastructure
  failures.
- If a rerun passes, report possible flakiness as a Hypothesis unless timing or
  scheduler evidence identifies the mechanism.

## iOS scheme or destination

Xcode cannot find the scheme or an eligible simulator destination.

- For a missing scheme, inspect the run's checkout for the shared scheme path
  named by the workflow.
- For destination errors, inspect the simulator-selection step output and the
  destination passed to the failing `xcodebuild` invocation.
- Require simulator-selection or destination evidence before classifying a
  missing simulator. A generic `xcodebuild` exit code is insufficient.

## Swift package resolution

Dependency resolution, checkout, or package graph construction fails before
build or tests.

- Distinguish network or service errors from an invalid revision, an
  incompatible package graph, and a changed lockfile.
- Check the exact dependency and revision in logs and at the run's SHA
  before recommending a rerun.

## iOS signing

The log names code signing, provisioning, certificates, or a team.

- Confirm whether the failing workflow command disables signing.
- Identify which target or build phase still requests signing.
- Require a signing-related decisive error block before classifying signing as
  the Supported cause.

## Environment-only or transient failures

The same command passes locally while CI fails, or logs show runner, network,
cache, disk, or hosted-service errors.

- Compare the run's OS image, toolchain setup, clean checkout, environment, cache
  use, and exact command against the local reproduction.
- Name the differing variable supported by evidence. If none is isolated,
  classify the environment difference as a Hypothesis.
- Recommend a rerun only when the failure is plausibly transient and the user
  has asked for a rerun or recommendation.
