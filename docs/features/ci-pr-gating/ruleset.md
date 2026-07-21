# Main branch ruleset (manual)

After this branch merges and the Checks workflows have run at least once on a
PR, configure repository rules (Settings → Rules → Rulesets) for `main`:

1. **Require a pull request before merging** — on.
2. **Require status checks to pass** — on.
3. **Required checks** — `android-checks`, `ios-checks` (exact job names from
   the Checks workflows).
4. **Require branches to be up to date before merging** — on (strict).
5. **Allow bypass** — off for admins if you want no escape hatch.

Pending and failed required checks both block merge. Draft PRs cannot merge
regardless of check status.

Do not use path filters on these required workflows.
