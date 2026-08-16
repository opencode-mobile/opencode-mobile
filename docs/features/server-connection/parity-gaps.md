# Server Connection Parity Gaps

## Android emulator host URL

- Expected shared intent: user reaches `opencode serve` on the development
  machine from the mobile app.
- Current iOS state: Simulator uses `http://127.0.0.1:4096/` in the connection
  form and saved profile.
- Current Android state: user must enter `http://10.0.2.2:4096/` on emulator;
  no automatic loopback rewrite for saved URLs.
- Affected area: behavior, manual-test documentation.
- Reason: explicit product decision — saved URL matches user input; emulator
  loopback differs from host loopback.
- Next action: document in manual-test; optional future hint copy in Android
  setup screen only.
- Status: intentional.

## Probe connect timeout

- Expected shared intent: dual-health detect uses a short connect budget and a
  5s request budget.
- Current iOS state: probe `URLSession` sets 5s request and 5s resource
  timeouts. There is no connect-only timeout.
- Current Android state: probe client uses 3s connect and 5s request/socket.
- Affected area: behavior, error handling.
- Reason: `URLSession` has no connect-only timeout separate from request /
  resource.
- Next action: revisit if a measured Tailscale connect budget is added.
- Status: intentional.
