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
