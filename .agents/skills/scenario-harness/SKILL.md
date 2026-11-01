---
name: scenario-harness
description: "Scenario harness: use to run the iOS or Android app against a real opencode v1 or v2 server with a scripted fake model, trigger scenarios (streaming, tools, permissions, questions, subagents, errors, aborts, SSE faults), and verify live app behaviour on a simulator or emulator."
---

# Scenario harness

`scripts/scenario-harness/` runs a real `opencode serve` whose only model is a
scripted fake, a fault proxy the apps connect through, and a web UI to trigger
scenarios and watch traffic. Use it when a change needs checking in the real
app against real server behaviour, on both protocols, without a paid model.
`node scripts/scenario-harness/harness.mjs --help` is the reference for flags.

| Piece | Where |
|---|---|
| Web UI and control API | `http://127.0.0.1:4500` |
| Apps connect to (fault proxy) | `:4096` on `0.0.0.0` — iOS sim `http://127.0.0.1:4096`, Android emulator `http://10.0.2.2:4096` |
| Real opencode (harness only) | `127.0.0.1:4097` |
| Fake model | `127.0.0.1:4501/v1` |
| Credentials | `opencode` / `<state-dir>/password` (stable across restarts) |
| Throwaway project | `<state-dir>/projects/acme-invoices`, seeded from `project-template/` |

## Start the server

Run it in the background and wait for `opencode <protocol> ready` in its log:

```bash
node scripts/scenario-harness/harness.mjs --protocol v1 --state-dir <scratch>/harness-state --fresh
```

- `--protocol v1` uses `opencode` on PATH (or `--v1-bin`). `--protocol v2`
  runs `~/Projects/opencode` (`dev`) from source (or `--v2-repo`); run
  `bun install` there first if dependencies are missing. Do not pull or edit
  that checkout unless asked.
- Switch protocol without restarting: `POST /harness/server/start {"protocol":"v2"}`
  or the Start v1/v2 buttons. Restart the process after editing harness code.
- `--fresh` re-seeds the project. Every Run/Arm resets project files with
  `git reset --hard && git clean -fd` (`keepFiles: true` skips it).
- State, data and config dirs are isolated from the user's real opencode data.
  Put `--state-dir` in the session scratchpad.
- Open the web UI in the built-in browser pane for the user; drive the API with
  `curl` yourself.

## Connect the apps

Respect device limits: at most one own iOS simulator and one Android emulator;
shut each down when its check is done.

**iOS (Debug build, signed — do not pass `CODE_SIGNING_ALLOWED=NO`).** Build
for the simulator, install, then launch with seeding args (this also wipes app
prefs):

```bash
xcrun simctl launch <udid> dev.opencode.mobile -OPENCODE_STORE_SERVER_URL http://127.0.0.1:4096 -OPENCODE_STORE_SERVER_PASSWORD <password>
```

Seeding adds every server project. Afterwards uninstall the app from the
simulator so its defaults do not leak into iOS unit tests.

**Android.** There is no launch-arg seeding. Build both APKs, install them,
clear data, seed through `HarnessSeedTest` with `am instrument` (not
`connectedDebugAndroidTest`, which uninstalls the app), then launch:

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

```bash
adb -s <serial> shell am instrument -w -e class dev.opencode.mobile.harness.HarnessSeedTest -e harnessUrl http://10.0.2.2:4096 -e harnessPassword <password> -e harnessProtocol v1 -e harnessWorktree <state-dir>/projects/acme-invoices dev.opencode.mobile.test/androidx.test.runner.AndroidJUnitRunner
```

```bash
adb -s <serial> shell am start -n dev.opencode.mobile/.MainActivity
```

Run `adb shell pm clear dev.opencode.mobile` before seeding. Always pass
`harnessWorktree`, or Home shows "No projects yet". Reseed with the matching
`harnessProtocol` after switching protocol.

## Run scenarios

Scenarios live in `scenarios.mjs` (P0), `scenarios-p1.mjs` and
`scenarios-p2.mjs`; `GET /harness/state` lists all 47 with priority, expected
app behaviour, variants and whether they need a device (`appOnly`). Three ways
to start one:

- **App drives:** `POST /harness/run {"scenario":"<id>"}` arms it; the next
  prompt sent from the app plays it. Tests the composer and send path.
- **Harness drives:** `POST /harness/run {"scenario":"<id>","drive":true}`
  creates a session and prompts it as another client. Watch from Home or open
  the session.
- **Marker prompt:** any prompt containing `scenario: <id>` starts that
  scenario in its session, from the app or from
  `POST /harness/action {"name":"prompt","sessionID":"...","text":"scenario: <id> ..."}`.
  Fastest way to run many scenarios in one open session.

Pass `"variant":"<name>"` to Run or Arm a variant. Scenarios with `setup` (seeded
sessions and turns for rename, delete, revert, compaction, other-client
prompts) run it before arming, so open the seeded session in the app while the
automation counts down. Unscripted prompts answer `ok`. `todo-list-progress` is
v1 only (v2 has no todo tool); the run endpoint refuses scenarios a protocol
cannot play. `tool-patch-multifile` needs the `gpt-5-scripted` harness model
(both servers only offer the patch tool to `gpt-*` ids); pick it in the app's
model picker when you type the prompt yourself.
Agent-run shells for the background work pill: `shell-foreground-stream`
(both protocols; a 60s foreground shell, a line every 0.5s), `shell-background`
(v2; a 3-minute background shell that outlives the turn) and
`shell-two-running` (v2; two background shells plus a subagent, "1 subagent ·
2 shells"). Their check variants (`probe`, `stop-from-outside`, `quick`) read
`/api/shell` and stop the shells as another client; play the base scenario to
try Stop from the app.
Scenarios with `automation` act as another client on server events (answer a
permission, abort, cut SSE) when started with Run; marker prompts do not run
automation, so trigger those steps yourself.

Useful control calls (all `POST`, JSON body):

| Goal | Call |
|---|---|
| Answer a pending ask as another client | `/harness/action {"name":"replyPermission","sessionID","requestID","reply":"once\|always\|reject"}`; pending ids in `GET /harness/state` → `pending` |
| Answer a question | `/harness/action {"name":"replyQuestion","sessionID","requestID","answers":[["<label>"]]}` |
| Abort, rename, delete | `/harness/action` with `abort`, `rename`, `remove` |
| List, read or stop a session's shells (v2) | `/harness/action` with `listShells`, `readShellOutputs` or `stopShells` and `sessionID`; one shell: `shellOutput {id, cursor}`, `stopShell {id}` |
| Release a hang or gate | `/harness/release {}` |
| Slow down or speed up streams | `/harness/pacing {"value":2}` |
| Drop app SSE now / offline / 401 | `/harness/fault/drop`, `/harness/fault {"offlineMs":4000}`, `/harness/fault {"rejectAuthMs":10000}`; 401 on event streams only: `{"rejectSseAuthMs":30000}` (state's `proxy.sseAttempts` counts reconnects) |
| Silent or heartbeat-only stream | `/harness/fault {"stall":true}` or `{"heartbeatOnly":true}`; clear with `/harness/fault/clear` |

Faults affect only the apps; the harness observer talks to opencode directly.

## Verify

- **Server side, headless:** `node scripts/scenario-harness/check.mjs [--priority P1] [--variants] [ids…]`
  plays scenarios with the harness as the user, auto-answers asks, aborts held
  turns nothing will release, and prints model calls, harness steps, tool
  states and event types per play. It runs a scenario's `checkVariant` by
  default; `--variants` adds every variant. "finished (server ended the turn;
  N optional call(s) unused)" is a pass for scenarios that declare
  `optionalCalls` (a reject ends the turn on both servers). Run it on both
  protocols after changing harness code or scenarios; under zsh split an ID
  list with `${=IDS}`.
- **App side:** screenshot after each step (iOS simulator tool or
  `xcrun simctl io <udid> screenshot`; `adb exec-out screencap -p`), compare
  with the scenario's `expect`, and cross-check the timeline:
  `GET /harness/history` holds app requests (with auth kind), server events,
  model calls and harness actions.
- **Evidence as you go:** when the change is user-visible, save each of those
  screenshots under the session scratchpad as
  `evidence/<platform>-<NN>-<step>.png` (`evidence/ios-03-answer-sheet.png`,
  `evidence/android-03-answer-sheet.png`; same `NN` for the same step on both
  apps). They become the pull request evidence (`feature` skill, Publish) with
  no re-recording. Harness data only.
- A feature change is verified only when it behaves on both apps and both
  protocols, or the gap is reported per platform and protocol.

## Add or change a scenario

Add an entry to the file for its priority; the header of `scenarios.mjs`
documents every field and automation action, and `scenario-kit.mjs` holds the
shared helpers (`run`, `path`, `subagent`, ...). In short: `prompt`, `expect`,
`modelCalls` (one entry per model request; steps `text`, `reason`, `tool`,
`pendingTool`, `wait {ms|gate}`, `hang`, `httpError {status, headers}`,
`streamError`, `raw`, `usage`, `finish`; `splitBytes`, `argChunkChars`,
`argDelayMs` for pacing), `child` lanes (subagent prompts bind automatically;
extra sessions get a static prompt in `lanes`), `summary` (compaction reply),
`setup`, `automation`, `variants`, `protocols`, `model`, `optionalCalls`,
`appOnly`, `checkVariant`, `serverChecks` (`{v1, v2}` lists of named
assertions in `check.mjs`, such as `bashOutputGrows` or `shellKilled`),
`checkTimeoutMs`, and `{v1, v2}` values where tool names or inputs
differ (`bash`/`shell`, `task`/`subagent`, `apply_patch`/`patch`, absolute
`filePath` vs relative `path`). Then run `check.mjs <id> --variants` on v1 and
v2.

## Traps

- "Authentication failed" on Android with requests showing auth `none`: the
  seed did not persist the password; reseed (the test sleeps so the
  credential store's `apply()` reaches disk).
- v2 health is `/api/info`; `/api/health` is 404 on `dev`.
- v2 questions are forms answered by field key (`{"answer":{"q0":"..."}}`);
  the harness maps UI answers onto field keys.
- A test command in a scenario runs for real in the project; keep scenario
  scripts consistent with the fixture or the tool row shows a real failure.
- v2 compaction asks in a user message ("You MUST summarize the conversation
  above") and only accepts a summary with its template headings
  (`## Objective`, `## Work State`, `## Next Move`, ...); v2 auto-compacts at
  the start of the next step, from the last reported usage.
- v1 background subagents need `OPENCODE_EXPERIMENTAL_BACKGROUND_SUBAGENTS=true`
  (the launcher sets it).
- v1 has no background shells. Aborting a running v1 `bash` call leaves the
  part `completed` with `metadata.exit: null` and `<shell_metadata>User aborted
  the command</shell_metadata>` appended to `output` (seen on 1.19.5).
- v2 wakes the session when a background shell ends, also when it is stopped
  (a `synthetic` inbox item with `metadata.source: "shell"`), so one more model
  call follows and answers `ok`.
- Gates are latched: a named release that arrives before its gate opens still
  lets it through, so release timings are safe to schedule early.
- `POST /harness/run` returns only after `setup` finishes, so opening "the
  newest session" in the app right after the call can land on an older one;
  wait for the seeded session to appear, or start the run in the background.

## Known app gaps the harness reproduces

Seen on 2026-10-17 (v1 1.19.1, v2 `dev`); recheck before reporting them again.

- A subagent's permission does not set Needs approval on the parent Home row
  (both apps). iOS shows no card in the parent or the child; Android shows it
  in the parent chat.
- v2 `patch` falls back to a generic "Called patch" row (both apps); v1
  `apply_patch` gets a Patch row with per-file counts.
- A session deleted by another client stays open in Session Chat with no
  removed state (both apps); Home drops the row.
- After an event-stream 401 the apps stop retrying (2-3 attempts) and stay on
  "Authentication failed" after auth recovers, through foreground and pull to
  refresh (both apps, both protocols).
- v1 revert from another client: iOS shows nothing; Android shows a
  "Reverting this message" banner. v2 staged revert: iOS hides the reverted
  turns immediately, Android only after commit.
- v1 tool rows show absolute paths (both apps).
- Stop the harness (`pkill -f scenario-harness/harness.mjs`) when done; it
  also stops opencode.
