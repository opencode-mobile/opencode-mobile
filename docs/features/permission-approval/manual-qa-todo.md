# Reliable Permission Test Setup

- [ ] Use a disposable test project or save its existing `opencode.json`.
- [ ] Create or replace the project-level `opencode.json` with:

  ```json
  {
    "$schema": "https://opencode.ai/config.json",
    "permission": {
      "bash": "ask"
    }
  }
  ```

- [ ] Restart `opencode serve` from that project directory so the configuration
  is loaded.
- [ ] Connect the mobile app to this server.
- [ ] Open or create a session using the **Build** agent.
- [ ] Send this prompt:

  > Use the bash tool to run exactly `printf 'permission-test\n'`. Do not answer
  > without running the command.

- [ ] Confirm the permission approval UI appears.
- [ ] After testing, restore the project's original `opencode.json`.
