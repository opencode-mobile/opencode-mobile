# Mac mini Runner — Ops Notes

Ownership: private Mac mini on LAN. No inbound ports, no relay.

## Runner identity

- Labels: `self-hosted`, `macOS`, `mac-mini` (same as `smoke-mac-mini.yml`, `ios-checks.yml`, `ios-build.yml`)
- Registration: repo-level self-hosted runner (Settings → Actions → Runners → New self-hosted runner → macOS ARM64)
- Pin drift intentionally: `sw_vers` + `xcodebuild -version` are logged in `Smoke Mac mini` and any failed iOS Checks run shows the current pinned versions. Bump Xcode/macOS deliberately.
- Current pin (2026-08-07, verified on `mac-mini-1`): macOS 26.5.2 (25F84), Xcode 26.6 (17F113), `xcode-select` → `/Applications/Xcode.app/Contents/Developer`, Runner 2.336.0. Update this line when Xcode/macOS is bumped.

## What runs where

| Workflow | Runner |
|---|---|
| iOS Checks | `[self-hosted, macOS, mac-mini]` |
| iOS Build | `[self-hosted, macOS, mac-mini]` |
| Smoke Mac mini (OPE-114 probe, keep until OPE-115 is stable) | `[self-hosted, macOS, mac-mini]` |
| All Android workflows (`android-checks`, `android-build`, `android-release`, `android-app-distribution`, `android-test-lab`) | `blacksmith-4vcpu-ubuntu-2404` — unchanged |

## Survives reboot / stays awake

1. Install as service (preferred over manual `./run.sh`):
   ```bash
   cd ~/actions-runner
   sudo ./svc.sh install
   sudo ./svc.sh start
   # verify
   sudo ./svc.sh status
   ```
   This creates a `LaunchAgent`/`LaunchDaemon` (depending on `svc.sh` version) that auto-starts on login/boot. If installed without `svc.sh`, add a LaunchAgent plist that runs `~/actions-runner/run.sh` on `RunAtLoad`.

2. Keep the mini awake enough for CI pickup:
   - System Settings → Lock Screen → Turn display off → Never (or 1h) while on power.
   - System Settings → Energy / Battery → Prevent automatic sleeping when display is off (or `sudo pmset -a disablesleep 0 sleep 0 displaysleep 30 disksleep 0`).
   - `caffeinate -dimsu` is not needed when `svc.sh` is active, but useful for debugging.
   - Power → Restart automatically after power failure: ON.
   - Ensure the user account that owns the runner auto-logs in if using a LaunchAgent (LaunchDaemon avoids this).

3. Verify after reboot:
   - GitHub → Settings → Actions → Runners → `mac-mini-1` shows **Idle** (green, `macOS` `ARM64` `self-hosted` `mac-mini`).
   - `cd ~/actions-runner && ./svc.sh status` → `Started: <pid> actions.runner...` and `launchctl print gui/$(id -u)/actions.runner...` shows `state = running` / `RunAtLoad = true`.
   - Dispatch `Smoke Mac mini` → green. If `xcode-select -p` drifts back to `/Library/Developer/CommandLineTools`, rerun `sudo xcode-select -s /Applications/Xcode.app/Contents/Developer`.

## Troubleshooting: duplicate sessions

If `stdout.log` shows `SessionConflictException` / `Runner listener exit with Session Conflict` and `launchctl list` shows no `actions.runner` while `ps aux | grep Runner.Listener` still shows a `run`-type listener, a manual `./run.sh` instance is colliding with the `svc.sh` LaunchAgent. Fix: `cd ~/actions-runner && ./svc.sh stop; kill <manual-pid>; ./svc.sh start`. The 2026-08-07 duplicate (`34304` manual vs `42903` service) was fixed this way; the service `43036` then reported `Listening for Jobs` cleanly.

## Recovery

- **Queued, never picked up**: Runner is offline. Check mini power + network (outbound 443 to `github.com`/`actions.githubusercontent.*` only), then `sudo ./svc.sh status` / `launchctl print gui/$UID/actions.runner.*`. Restart: `sudo ./svc.sh stop && sudo ./svc.sh start`. Logs: `~/actions-runner/_diag/*.log` and `svc.sh` service logs.
- **Failed after checkout**: Likely Xcode / CLT drift. Run `xcodebuild -version`, `xcode-select -p`, `sw_vers` on the mini, reinstall CLTs (`xcode-select --install`) or re-point `sudo xcode-select -s /Applications/Xcode.app`.
- **Gradle / JDK**: iOS Checks sets up Temurin 17 via `actions/setup-java`; no local JDK install required, but keep `java -version` healthy for Smoke.
- **Re-register if token expired**: Remove stale runner in GitHub UI, then re-run `config.sh` with a fresh token from the New runner page. Labels and name must stay `mac-mini` (or update workflows to match).

## Optional Blacksmith fallback (when the mini is offline)

No code change is needed beyond the 2-line `runs-on`. To fall back:

```diff
# .github/workflows/ios-checks.yml
-    runs-on: [self-hosted, macOS, mac-mini]
+    runs-on: blacksmith-6vcpu-macos-26

# .github/workflows/ios-build.yml
-    runs-on: [self-hosted, macOS, mac-mini]
+    runs-on: blacksmith-6vcpu-macos-26
```

Commit on a branch or directly on `main` if the mini will be down for an extended window, then revert when it is back online. Keep Android unchanged. Do not switch to Blacksmith macOS permanently without updating this doc and the pinned Xcode note above.

## Smoke probe

`Smoke Mac mini` (`workflow_dispatch`) is the OPE-114 probe. Keep it until iOS Checks has had several green runs on the mini, then delete `.github/workflows/smoke-mac-mini.yml` if desired.
