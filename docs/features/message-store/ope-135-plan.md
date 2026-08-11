# OPE-135 iOS off-main timeline and display pipeline

Parent feature: [Message Store](plan.md)
Linear: [OPE-135](https://linear.app/opencode-mobile/issue/OPE-135/ios-off-main-timeline-and-display-pipeline)

## Goal

Heavy Session Chat work must not run on the main thread. MainActor only
publishes immutable UI frames. Matches Android worker projection.

## In scope

1. Off-main `DisplayFrameBuilder` + `SessionChatTimelineProjector` (+ blocks).
2. `DisplayMessagePipeline` orchestration off MainActor; lock
   `PreparedHistoryCache`.
3. Regression test that structural frame build is off main.
4. Docs / parity gap update for structural projection.

## Out of scope (follow-up)

- Per-event SSE → LiveStore MainActor fan-out redesign — tracked as Stage 2 of
  [ios-concurrency-freeze-remediation-plan.md](./ios-concurrency-freeze-remediation-plan.md).
- Removing `SWIFT_DEFAULT_ACTOR_ISOLATION = MainActor` project-wide.
- Stack-capturing hang watchdog (OPE-134) — Stage 0 of that remediation plan.

## Expected result

Release/TestFlight freezes from timeline projection / pipeline orchestration
should stop monopolizing MainActor. Hang watchdog + Diagnostic Logs remain the
catch net for residual freezes.
